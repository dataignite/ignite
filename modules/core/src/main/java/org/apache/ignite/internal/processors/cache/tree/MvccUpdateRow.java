/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.tree;

import java.util.ArrayList;
import java.util.List;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.internal.processors.cache.CacheObject;
import org.apache.ignite.internal.processors.cache.KeyCacheObject;
import org.apache.ignite.internal.processors.cache.mvcc.CacheCoordinatorsProcessor;
import org.apache.ignite.internal.processors.cache.mvcc.MvccCoordinatorVersion;
import org.apache.ignite.internal.processors.cache.persistence.CacheDataRow;
import org.apache.ignite.internal.processors.cache.persistence.CacheSearchRow;
import org.apache.ignite.internal.processors.cache.persistence.tree.BPlusTree;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.BPlusIO;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.util.GridLongList;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.internal.processors.cache.mvcc.CacheCoordinatorsProcessor.unmaskCoordinatorVersion;

/**
 *
 */
public class MvccUpdateRow extends DataRow implements BPlusTree.TreeRowClosure<CacheSearchRow, CacheDataRow> {
    /** */
    private UpdateResult res;

    /** */
    private boolean canCleanup;

    /** */
    private GridLongList activeTxs;

    /** */
    private List<CacheSearchRow> cleanupRows;

    /** */
    private final MvccCoordinatorVersion mvccVer;

    /**
     * @param key Key.
     * @param val Value.
     * @param ver Version.
     * @param mvccVer Mvcc version.
     * @param part Partition.
     * @param cacheId Cache ID.
     */
    public MvccUpdateRow(
        KeyCacheObject key,
        CacheObject val,
        GridCacheVersion ver,
        MvccCoordinatorVersion mvccVer,
        int part,
        int cacheId) {
        super(key, val, ver, part, 0L, cacheId);

        this.mvccVer = mvccVer;
    }

    /**
     * @return {@code True} if previous value was non-null.
     */
    public UpdateResult updateResult() {
        return res == null ? UpdateResult.PREV_NULL : res;
    }

    /**
     * @return Active transactions to wait for.
     */
    @Nullable public GridLongList activeTransactions() {
        return activeTxs;
    }

    /**
     * @return Rows which are safe to cleanup.
     */
    public List<CacheSearchRow> cleanupRows() {
        return cleanupRows;
    }

    /**
     * @param io IO.
     * @param pageAddr Page address.
     * @param idx Item index.
     * @return Always {@code true}.
     */
    private boolean assertVersion(RowLinkIO io, long pageAddr, int idx) {
        long rowCrdVer = unmaskCoordinatorVersion(io.getMvccCoordinatorVersion(pageAddr, idx));
        long rowCntr = io.getMvccCounter(pageAddr, idx);

        int cmp = Long.compare(mvccVer.coordinatorVersion(), rowCrdVer);

        if (cmp == 0)
            cmp = Long.compare(mvccVer.counter(), rowCntr);

        // Can be equals if backup rebalanced value updated on primary.
        assert cmp >= 0 : "[updCrd=" + mvccVer.coordinatorVersion() +
            ", updCntr=" + mvccVer.counter() +
            ", rowCrd=" + rowCrdVer +
            ", rowCntr=" + rowCntr + ']';

        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean apply(BPlusTree<CacheSearchRow, CacheDataRow> tree,
        BPlusIO<CacheSearchRow> io,
        long pageAddr,
        int idx)
        throws IgniteCheckedException
    {
        RowLinkIO rowIo = (RowLinkIO)io;

        // Assert version grows.
        assert assertVersion(rowIo, pageAddr, idx);

        boolean checkActive = mvccVer.activeTransactions().size() > 0;

        boolean txActive = false;

        long rowCrdVerMasked = rowIo.getMvccCoordinatorVersion(pageAddr, idx);
        long rowCrdVer = unmaskCoordinatorVersion(rowCrdVerMasked);

        if (res == null) {
            int cmp = Long.compare(mvccVer.coordinatorVersion(), rowCrdVer);

            if (cmp == 0)
                cmp = Long.compare(mvccVer.coordinatorVersion(), rowIo.getMvccCounter(pageAddr, idx));

            if (cmp == 0)
                res = UpdateResult.VERSION_FOUND;
            else
                res = CacheCoordinatorsProcessor.versionForRemovedValue(rowCrdVerMasked) ?
                    UpdateResult.PREV_NULL : UpdateResult.PREV_NOT_NULL;
        }

        // Suppose transactions on previous coordinator versions are done.
        if (checkActive && mvccVer.coordinatorVersion() == rowCrdVer) {
            long rowMvccCntr = rowIo.getMvccCounter(pageAddr, idx);

            if (mvccVer.activeTransactions().contains(rowMvccCntr)) {
                txActive = true;

                if (activeTxs == null)
                    activeTxs = new GridLongList();

                activeTxs.add(rowMvccCntr);
            }
        }

        if (!txActive) {
            assert Long.compare(mvccVer.coordinatorVersion(), rowCrdVer) >= 0;

            int cmp;

            if (mvccVer.coordinatorVersion() == rowCrdVer)
                cmp = Long.compare(mvccVer.cleanupVersion(), rowIo.getMvccCounter(pageAddr, idx));
            else
                cmp = 1;

            if (cmp >= 0) {
                // Do not cleanup oldest version.
                if (canCleanup) {
                    CacheSearchRow row = io.getLookupRow(tree, pageAddr, idx);

                    assert row.link() != 0 && row.mvccCounter() != CacheCoordinatorsProcessor.COUNTER_NA : row;

                    // Should not be possible to cleanup active tx.
                    assert rowCrdVer != mvccVer.coordinatorVersion()
                        || !mvccVer.activeTransactions().contains(row.mvccCounter());

                    if (cleanupRows == null)
                        cleanupRows = new ArrayList<>();

                    cleanupRows.add(row);
                }
                else
                    canCleanup = true;
            }
        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public long mvccCoordinatorVersion() {
        return mvccVer.coordinatorVersion();
    }

    /** {@inheritDoc} */
    @Override public long mvccCounter() {
        return mvccVer.counter();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(MvccUpdateRow.class, this, "super", super.toString());
    }

    /**
     *
     */
    public enum UpdateResult {
        /** */
        VERSION_FOUND,
        /** */
        PREV_NULL,
        /** */
        PREV_NOT_NULL
    }
}
