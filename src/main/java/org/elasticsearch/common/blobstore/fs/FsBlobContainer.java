/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.blobstore.fs;

import com.google.common.collect.ImmutableMap;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.common.blobstore.BlobMetaData;
import org.elasticsearch.common.blobstore.BlobPath;
import org.elasticsearch.common.blobstore.support.AbstractBlobContainer;
import org.elasticsearch.common.blobstore.support.PlainBlobMetaData;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.io.FileSystemUtils;

import java.io.*;

/**
 *
 */
public class FsBlobContainer extends AbstractBlobContainer {

    protected final FsBlobStore blobStore;

    protected final File path;

    public FsBlobContainer(FsBlobStore blobStore, BlobPath blobPath, File path) {
        super(blobPath);
        this.blobStore = blobStore;
        this.path = path;
    }

    public File filePath() {
        return this.path;
    }

    public ImmutableMap<String, BlobMetaData> listBlobs() throws IOException {
        File[] files = path.listFiles();
        if (files == null || files.length == 0) {
            return ImmutableMap.of();
        }
        // using MapBuilder and not ImmutableMap.Builder as it seems like File#listFiles might return duplicate files!
        MapBuilder<String, BlobMetaData> builder = MapBuilder.newMapBuilder();
        for (File file : files) {
            if (file.isFile()) {
                builder.put(file.getName(), new PlainBlobMetaData(file.getName(), file.length()));
            }
        }
        return builder.immutableMap();
    }

    public boolean deleteBlob(String blobName) throws IOException {
        return new File(path, blobName).delete();
    }

    @Override
    public boolean blobExists(String blobName) {
        return new File(path, blobName).exists();
    }

    @Override
    public InputStream openInput(String name) throws IOException {
        return new BufferedInputStream(new FileInputStream(new File(path, name)), blobStore.bufferSizeInBytes());
    }

    @Override
    public OutputStream createOutput(String blobName) throws IOException {
        final File file = new File(path, blobName);
        return new BufferedOutputStream(new FilterOutputStream(new FileOutputStream(file)) {

            @Override // FilterOutputStream#write(byte[] b, int off, int len) is trappy writes every single byte
            public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len);}

            @Override
            public void close() throws IOException {
                super.close();
                IOUtils.fsync(file, false);
                IOUtils.fsync(path, true);
            }
        }, blobStore.bufferSizeInBytes());
    }
}
