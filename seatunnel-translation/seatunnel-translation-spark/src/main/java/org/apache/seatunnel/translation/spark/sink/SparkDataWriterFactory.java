/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.translation.spark.sink;

import org.apache.seatunnel.api.sink.SinkCommitter;
import org.apache.seatunnel.api.sink.SinkWriter;
import org.apache.seatunnel.api.table.type.SeaTunnelRow;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.sources.v2.writer.DataWriter;
import org.apache.spark.sql.sources.v2.writer.DataWriterFactory;
import org.apache.spark.sql.types.StructType;

import javax.annotation.Nullable;

public class SparkDataWriterFactory<CommitInfoT, StateT> implements DataWriterFactory<InternalRow> {

    private final SinkWriter<SeaTunnelRow, CommitInfoT, StateT> sinkWriter;
    @Nullable
    private final SinkCommitter<CommitInfoT> sinkCommitter;
    private final StructType schema;

    SparkDataWriterFactory(SinkWriter<SeaTunnelRow, CommitInfoT, StateT> sinkWriter,
                           @Nullable SinkCommitter<CommitInfoT> sinkCommitter,
                           StructType schema) {
        this.sinkWriter = sinkWriter;
        this.sinkCommitter = sinkCommitter;
        this.schema = schema;
    }

    @Override
    public DataWriter<InternalRow> createDataWriter(int partitionId, long taskId, long epochId) {
        // TODO use partitionID, taskId information.
        return new SparkDataWriter<>(sinkWriter, sinkCommitter, schema, epochId);
    }
}