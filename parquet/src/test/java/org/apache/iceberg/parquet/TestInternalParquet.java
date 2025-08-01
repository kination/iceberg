/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.parquet;

import java.io.IOException;
import java.util.List;
import org.apache.iceberg.InternalTestHelpers;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.RandomInternalData;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.data.DataTestBase;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.InternalReader;
import org.apache.iceberg.data.parquet.InternalWriter;
import org.apache.iceberg.inmemory.InMemoryOutputFile;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;

public class TestInternalParquet extends DataTestBase {
  @Override
  protected boolean supportsDefaultValues() {
    return true;
  }

  @Override
  protected boolean supportsUnknown() {
    return true;
  }

  @Override
  protected boolean supportsTimestampNanos() {
    return true;
  }

  @Override
  protected boolean supportsVariant() {
    return true;
  }

  @Override
  protected void writeAndValidate(Schema schema) throws IOException {
    List<Record> expected = RandomInternalData.generate(schema, 100, 1376L);
    writeAndValidate(schema, expected);
  }

  @Override
  protected void writeAndValidate(Schema writeSchema, Schema expectedSchema) throws IOException {
    List<Record> expected = RandomInternalData.generate(writeSchema, 100, 1376L);
    writeAndValidate(writeSchema, expectedSchema, expected);
  }

  @Override
  protected void writeAndValidate(Schema schema, List<Record> expected) throws IOException {
    writeAndValidate(schema, schema, expected);
  }

  protected void writeAndValidate(Schema writeSchema, Schema expectedSchema, List<Record> expected)
      throws IOException {
    OutputFile outputFile = new InMemoryOutputFile();

    try (DataWriter<StructLike> dataWriter =
        Parquet.writeData(outputFile)
            .schema(writeSchema)
            .createWriterFunc(InternalWriter::createWriter)
            .overwrite()
            .withSpec(PartitionSpec.unpartitioned())
            .build()) {
      for (Record record : expected) {
        dataWriter.write(record);
      }
    }

    List<Record> rows;
    try (CloseableIterable<Record> reader =
        Parquet.read(outputFile.toInputFile())
            .project(expectedSchema)
            .createReaderFunc(fileSchema -> InternalReader.create(expectedSchema, fileSchema))
            .build()) {
      rows = Lists.newArrayList(reader);
    }

    for (int i = 0; i < expected.size(); i += 1) {
      InternalTestHelpers.assertEquals(expectedSchema.asStruct(), expected.get(i), rows.get(i));
    }

    // test reuseContainers
    try (CloseableIterable<Record> reader =
        Parquet.read(outputFile.toInputFile())
            .project(expectedSchema)
            .reuseContainers()
            .createReaderFunc(fileSchema -> InternalReader.create(expectedSchema, fileSchema))
            .build()) {
      int index = 0;
      for (Record actualRecord : reader) {
        InternalTestHelpers.assertEquals(
            expectedSchema.asStruct(), expected.get(index), actualRecord);
        index += 1;
      }
    }
  }
}
