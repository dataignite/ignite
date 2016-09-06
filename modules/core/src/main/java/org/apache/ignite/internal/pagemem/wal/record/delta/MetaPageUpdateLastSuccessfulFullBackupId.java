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

package org.apache.ignite.internal.pagemem.wal.record.delta;

import java.nio.ByteBuffer;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.internal.processors.cache.database.tree.io.PageMetaIO;

/**
 *
 */
public class MetaPageUpdateLastSuccessfulFullBackupId extends PageDeltaRecord {
    /** */
    private final long lastSuccessfulFullBackupId;

    /**
     * @param pageId Meta page ID.
     */
    public MetaPageUpdateLastSuccessfulFullBackupId(int cacheId, long pageId, long lastSuccessfulFullBackupId) {
        super(cacheId, pageId);

        this.lastSuccessfulFullBackupId = lastSuccessfulFullBackupId;
    }

    /** {@inheritDoc} */
    @Override public void applyDelta(ByteBuffer buf) throws IgniteCheckedException {
        PageMetaIO io = PageMetaIO.VERSIONS.forPage(buf);

        io.setLastSuccessfulFullBackupId(buf, lastSuccessfulFullBackupId);
    }

    /** {@inheritDoc} */
    @Override public RecordType type() {
        return RecordType.META_PAGE_UPDATE_LAST_SUCCESSFUL_FULL_BACKUP_ID;
    }

    /**
     * @return Root ID.
     */
    public long lastSuccessfulFullBackupId() {
        return lastSuccessfulFullBackupId;
    }
}
