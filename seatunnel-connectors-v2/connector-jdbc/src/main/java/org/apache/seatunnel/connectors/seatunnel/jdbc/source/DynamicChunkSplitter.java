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

package org.apache.seatunnel.connectors.seatunnel.jdbc.source;

import org.apache.seatunnel.api.table.catalog.TablePath;
import org.apache.seatunnel.api.table.type.SeaTunnelDataType;
import org.apache.seatunnel.api.table.type.SeaTunnelRowType;
import org.apache.seatunnel.connectors.seatunnel.jdbc.config.JdbcSourceConfig;
import org.apache.seatunnel.connectors.seatunnel.jdbc.utils.ObjectUtils;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import static java.math.BigDecimal.ROUND_CEILING;
import static org.apache.seatunnel.shade.com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class DynamicChunkSplitter extends ChunkSplitter {

    public DynamicChunkSplitter(JdbcSourceConfig config) {
        super(config);
    }

    @Override
    protected Collection<JdbcSourceSplit> createSplits(
            JdbcSourceTable table, SeaTunnelRowType splitKey) throws SQLException {
        return createDynamicSplits(table, splitKey);
    }

    @Override
    protected PreparedStatement createSplitStatement(JdbcSourceSplit split) throws SQLException {
        return createDynamicSplitStatement(split);
    }

    private Collection<JdbcSourceSplit> createDynamicSplits(
            JdbcSourceTable table, SeaTunnelRowType splitKey) throws SQLException {
        String splitKeyName = splitKey.getFieldNames()[0];
        SeaTunnelDataType splitKeyType = splitKey.getFieldType(0);
        List<ChunkRange> chunks = splitTableIntoChunks(table, splitKeyName, splitKeyType);

        List<JdbcSourceSplit> splits = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkRange chunk = chunks.get(i);
            JdbcSourceSplit split =
                    new JdbcSourceSplit(
                            table.getTablePath(),
                            createSplitId(table.getTablePath(), i),
                            table.getQuery(),
                            splitKeyName,
                            splitKeyType,
                            chunk.getChunkStart(),
                            chunk.getChunkEnd());
            splits.add(split);
        }
        return splits;
    }

    private PreparedStatement createDynamicSplitStatement(JdbcSourceSplit split)
            throws SQLException {
        String splitQuery = createDynamicSplitQuerySQL(split);
        PreparedStatement statement = createPreparedStatement(splitQuery);
        prepareDynamicSplitStatement(statement, split);
        return statement;
    }

    private List<ChunkRange> splitTableIntoChunks(
            JdbcSourceTable table, String splitColumnName, SeaTunnelDataType splitColumnType)
            throws SQLException {
        TablePath tablePath = table.getTablePath();
        Pair<Object, Object> minMax = queryMinMax(table, splitColumnName);
        Object min = minMax.getLeft();
        Object max = minMax.getRight();
        if (min == null || max == null || min.equals(max)) {
            // empty table, or only one row, return full table scan as a chunk
            return Collections.singletonList(ChunkRange.all());
        }

        int chunkSize = config.getSplitSize();
        double distributionFactorUpper = config.getSplitEvenDistributionFactorUpperBound();
        double distributionFactorLower = config.getSplitEvenDistributionFactorLowerBound();
        int sampleShardingThreshold = config.getSplitSampleShardingThreshold();

        log.info(
                "Splitting table {} into chunks, split column: {}, min: {}, max: {}, chunk size: {}, "
                        + "distribution factor upper: {}, distribution factor lower: {}, sample sharding threshold: {}",
                tablePath,
                splitColumnName,
                min,
                max,
                chunkSize,
                distributionFactorUpper,
                distributionFactorLower,
                sampleShardingThreshold);

        if (isEvenlySplitColumn(splitColumnType)) {
            long approximateRowCnt = queryApproximateRowCnt(table);
            double distributionFactor =
                    calculateDistributionFactor(tablePath, min, max, approximateRowCnt);

            boolean dataIsEvenlyDistributed =
                    ObjectUtils.doubleCompare(distributionFactor, distributionFactorLower) >= 0
                            && ObjectUtils.doubleCompare(
                                            distributionFactor, distributionFactorUpper)
                                    <= 0;

            if (dataIsEvenlyDistributed) {
                // the minimum dynamic chunk size is at least 1
                final int dynamicChunkSize = Math.max((int) (distributionFactor * chunkSize), 1);
                return splitEvenlySizedChunks(
                        tablePath, min, max, approximateRowCnt, chunkSize, dynamicChunkSize);
            } else {
                int shardCount = (int) (approximateRowCnt / chunkSize);
                int inverseSamplingRate = config.getSplitInverseSamplingRate();
                if (sampleShardingThreshold < shardCount) {
                    // It is necessary to ensure that the number of data rows sampled by the
                    // sampling rate is greater than the number of shards.
                    // Otherwise, if the sampling rate is too low, it may result in an insufficient
                    // number of data rows for the shards, leading to an inadequate number of
                    // shards.
                    // Therefore, inverseSamplingRate should be less than chunkSize
                    if (inverseSamplingRate > chunkSize) {
                        log.warn(
                                "The inverseSamplingRate is {}, which is greater than chunkSize {}, so we set inverseSamplingRate to chunkSize",
                                inverseSamplingRate,
                                chunkSize);
                        inverseSamplingRate = chunkSize;
                    }
                    log.info(
                            "Use sampling sharding for table {}, the sampling rate is {}",
                            tablePath,
                            inverseSamplingRate);
                    Object[] sample =
                            jdbcDialect.sampleDataFromColumn(
                                    getOrEstablishConnection(),
                                    table,
                                    splitColumnName,
                                    inverseSamplingRate);
                    log.info(
                            "Sample data from table {} end, the sample size is {}",
                            tablePath,
                            sample.length);
                    return efficientShardingThroughSampling(
                            tablePath, sample, approximateRowCnt, shardCount);
                }
                return splitUnevenlySizedChunks(table, splitColumnName, min, max, chunkSize);
            }
        } else {
            return splitUnevenlySizedChunks(table, splitColumnName, min, max, chunkSize);
        }
    }

    private Long queryApproximateRowCnt(JdbcSourceTable table) throws SQLException {
        return jdbcDialect.approximateRowCntStatement(getOrEstablishConnection(), table);
    }

    private double calculateDistributionFactor(
            TablePath tablePath, Object min, Object max, long approximateRowCnt) {

        if (!min.getClass().equals(max.getClass())) {
            throw new IllegalStateException(
                    String.format(
                            "Unsupported operation type, the MIN value type %s is different with MAX value type %s.",
                            min.getClass().getSimpleName(), max.getClass().getSimpleName()));
        }
        if (approximateRowCnt == 0) {
            return Double.MAX_VALUE;
        }
        BigDecimal difference = ObjectUtils.minus(max, min);
        // factor = (max - min + 1) / rowCount
        final BigDecimal subRowCnt = difference.add(BigDecimal.valueOf(1));
        double distributionFactor =
                subRowCnt.divide(new BigDecimal(approximateRowCnt), 4, ROUND_CEILING).doubleValue();
        log.info(
                "The distribution factor of table {} is {} according to the min split key {}, max split key {} and approximate row count {}",
                tablePath,
                distributionFactor,
                min,
                max,
                approximateRowCnt);
        return distributionFactor;
    }

    private List<ChunkRange> splitEvenlySizedChunks(
            TablePath tablePath,
            Object min,
            Object max,
            long approximateRowCnt,
            int chunkSize,
            int dynamicChunkSize) {
        log.info(
                "Use evenly-sized chunk optimization for table {}, the approximate row count is {}, the chunk size is {}, the dynamic chunk size is {}",
                tablePath,
                approximateRowCnt,
                chunkSize,
                dynamicChunkSize);
        if (approximateRowCnt <= chunkSize) {
            // there is no more than one chunk, return full table as a chunk
            return Collections.singletonList(ChunkRange.all());
        }

        final List<ChunkRange> splits = new ArrayList<>();
        Object chunkStart = null;
        Object chunkEnd = ObjectUtils.plus(min, dynamicChunkSize);
        while (ObjectUtils.compare(chunkEnd, max) <= 0) {
            splits.add(ChunkRange.of(chunkStart, chunkEnd));
            chunkStart = chunkEnd;
            try {
                chunkEnd = ObjectUtils.plus(chunkEnd, dynamicChunkSize);
            } catch (ArithmeticException e) {
                // Stop chunk split to avoid dead loop when number overflows.
                break;
            }
        }
        // add the ending split
        splits.add(ChunkRange.of(chunkStart, null));
        return splits;
    }

    private List<ChunkRange> efficientShardingThroughSampling(
            TablePath tablePath, Object[] sampleData, long approximateRowCnt, int shardCount) {
        log.info(
                "Use efficient sharding through sampling optimization for table {}, the approximate row count is {}, the shardCount is {}",
                tablePath,
                approximateRowCnt,
                shardCount);

        final List<ChunkRange> splits = new ArrayList<>();

        if (shardCount == 0) {
            splits.add(ChunkRange.of(null, null));
            return splits;
        }

        double approxSamplePerShard = (double) sampleData.length / shardCount;

        if (approxSamplePerShard <= 1) {

            splits.add(ChunkRange.of(null, sampleData[0]));
            for (int i = 0; i < sampleData.length - 1; i++) {
                splits.add(ChunkRange.of(sampleData[i], sampleData[i + 1]));
            }
            splits.add(ChunkRange.of(sampleData[sampleData.length - 1], null));
        } else {
            // Calculate the shard boundaries
            for (int i = 0; i < shardCount; i++) {
                Object chunkStart = i == 0 ? null : sampleData[(int) (i * approxSamplePerShard)];
                Object chunkEnd =
                        i < shardCount - 1
                                ? sampleData[(int) ((i + 1) * approxSamplePerShard)]
                                : null;
                splits.add(ChunkRange.of(chunkStart, chunkEnd));
            }
        }
        return splits;
    }

    private List<ChunkRange> splitUnevenlySizedChunks(
            JdbcSourceTable table, String splitColumnName, Object min, Object max, int chunkSize)
            throws SQLException {
        log.info(
                "Use unevenly-sized chunks for table {}, the chunk size is {}",
                table.getTablePath(),
                chunkSize);
        final List<ChunkRange> splits = new ArrayList<>();
        Object chunkStart = null;
        Object chunkEnd = nextChunkEnd(min, table, splitColumnName, max, chunkSize);
        int count = 0;
        while (chunkEnd != null && objectCompare(chunkEnd, max) <= 0) {
            // we start from [null, min + chunk_size) and avoid [null, min)
            splits.add(ChunkRange.of(chunkStart, chunkEnd));
            // may sleep a while to avoid DDOS on MySQL server
            maySleep(count++, table.getTablePath());
            chunkStart = chunkEnd;
            chunkEnd = nextChunkEnd(chunkEnd, table, splitColumnName, max, chunkSize);
        }
        // add the ending split
        splits.add(ChunkRange.of(chunkStart, null));
        return splits;
    }

    private Object nextChunkEnd(
            Object previousChunkEnd,
            JdbcSourceTable table,
            String splitColumnName,
            Object max,
            int chunkSize)
            throws SQLException {
        // chunk end might be null when max values are removed
        Object chunkEnd =
                jdbcDialect.queryNextChunkMax(
                        getOrEstablishConnection(),
                        table,
                        splitColumnName,
                        chunkSize,
                        previousChunkEnd);
        if (Objects.equals(previousChunkEnd, chunkEnd)) {
            // we don't allow equal chunk start and end,
            // should query the next one larger than chunkEnd
            chunkEnd = queryMin(table, splitColumnName, chunkEnd);
        }
        if (objectCompare(chunkEnd, max) >= 0) {
            return null;
        } else {
            return chunkEnd;
        }
    }

    private static void maySleep(int count, TablePath tablePath) {
        // every 100 queries to sleep 1s
        if (count % 10 == 0) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // nothing to do
            }
            log.info("DynamicChunkSplitter has split {} chunks for table {}", count, tablePath);
        }
    }

    private int objectCompare(Object obj1, Object obj2) {
        return ObjectUtils.compare(obj1, obj2);
    }

    private String createDynamicSplitQuerySQL(JdbcSourceSplit split) {
        SeaTunnelRowType rowType =
                new SeaTunnelRowType(
                        new String[] {split.getSplitKeyName()},
                        new SeaTunnelDataType[] {split.getSplitKeyType()});
        boolean isFirstSplit = split.getSplitStart() == null;
        boolean isLastSplit = split.getSplitEnd() == null;

        final String condition;
        if (isFirstSplit && isLastSplit) {
            condition = null;
        } else if (isFirstSplit) {
            StringBuilder sql = new StringBuilder();
            addKeyColumnsToCondition(rowType, sql, " <= ?");
            sql.append(" AND NOT (");
            addKeyColumnsToCondition(rowType, sql, " = ?");
            sql.append(")");
            condition = sql.toString();
        } else if (isLastSplit) {
            StringBuilder sql = new StringBuilder();
            addKeyColumnsToCondition(rowType, sql, " >= ?");
            condition = sql.toString();
        } else {
            StringBuilder sql = new StringBuilder();
            addKeyColumnsToCondition(rowType, sql, " >= ?");
            sql.append(" AND NOT (");
            addKeyColumnsToCondition(rowType, sql, " = ?");
            sql.append(")");
            sql.append(" AND ");
            addKeyColumnsToCondition(rowType, sql, " <= ?");
            condition = sql.toString();
        }

        String splitQuery = split.getSplitQuery();
        if (StringUtils.isNotBlank(splitQuery)) {
            splitQuery = String.format("SELECT * FROM (%s) tmp", splitQuery);
        } else {
            splitQuery =
                    String.format(
                            "SELECT * FROM %s", jdbcDialect.tableIdentifier(split.getTablePath()));
        }

        StringBuilder sql = new StringBuilder();
        sql.append(splitQuery);
        if (!StringUtils.isEmpty(condition)) {
            sql.append(" WHERE ").append(condition);
        }
        return sql.toString();
    }

    private static void addKeyColumnsToCondition(
            SeaTunnelRowType rowType, StringBuilder sql, String predicate) {
        for (Iterator<String> fieldNamesIt = Arrays.stream(rowType.getFieldNames()).iterator();
                fieldNamesIt.hasNext(); ) {
            sql.append(fieldNamesIt.next()).append(predicate);
            if (fieldNamesIt.hasNext()) {
                sql.append(" AND ");
            }
        }
    }

    private static void prepareDynamicSplitStatement(
            PreparedStatement statement, JdbcSourceSplit split) throws SQLException {
        boolean isFirstSplit = split.getSplitStart() == null;
        boolean isLastSplit = split.getSplitEnd() == null;
        if (isFirstSplit && isLastSplit) {
            return;
        }

        Object[] splitStart = new Object[] {split.getSplitStart()};
        Object[] splitEnd = new Object[] {split.getSplitEnd()};
        int splitKeyNumbers = 1;
        if (isFirstSplit) {
            for (int i = 0; i < splitKeyNumbers; i++) {
                statement.setObject(i + 1, splitEnd[i]);
                statement.setObject(i + 1 + splitKeyNumbers, splitEnd[i]);
            }
        } else if (isLastSplit) {
            for (int i = 0; i < splitKeyNumbers; i++) {
                statement.setObject(i + 1, splitStart[i]);
            }
        } else {
            for (int i = 0; i < splitKeyNumbers; i++) {
                statement.setObject(i + 1, splitStart[i]);
                statement.setObject(i + 1 + splitKeyNumbers, splitEnd[i]);
                statement.setObject(i + 1 + 2 * splitKeyNumbers, splitEnd[i]);
            }
        }
    }

    @Data
    @EqualsAndHashCode
    private static class ChunkRange implements Serializable {
        private final Object chunkStart;
        private final Object chunkEnd;

        public static ChunkRange all() {
            return new ChunkRange(null, null);
        }

        public static ChunkRange of(Object chunkStart, Object chunkEnd) {
            return new ChunkRange(chunkStart, chunkEnd);
        }

        private ChunkRange(Object chunkStart, Object chunkEnd) {
            if (chunkStart != null || chunkEnd != null) {
                checkArgument(
                        !Objects.equals(chunkStart, chunkEnd),
                        "Chunk start %s shouldn't be equal to chunk end %s",
                        chunkStart,
                        chunkEnd);
            }
            this.chunkStart = chunkStart;
            this.chunkEnd = chunkEnd;
        }
    }
}
