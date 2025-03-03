/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dremio.exec.store.deltalake;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.dremio.io.file.Path;
import com.dremio.service.namespace.file.proto.FileType;

/**
 * Tests for {@link DeltaFilePathResolver}
 */
public class TestDeltaFilePathResolver {

  @Test
  public void testFilePathResolver() {
    Path metaDir = Path.of(".");

    DeltaFilePathResolver resolver = new DeltaFilePathResolver();

    Path path = resolver.resolve(metaDir, 0L, 1L, FileType.JSON).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000000.json"));

    path = resolver.resolve(metaDir, 1L, 0L, FileType.JSON).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000001.json"));

    path = resolver.resolve(metaDir, 1L, 1L, FileType.PARQUET).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000001.checkpoint.parquet"));

    path = resolver.resolve(metaDir, 10L, 1L, FileType.JSON).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000010.json"));

    path = resolver.resolve(metaDir, 22L, 1L, FileType.JSON).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000022.json"));

    path = resolver.resolve(metaDir , 10L, 1L, FileType.PARQUET).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000010.checkpoint.parquet"));

    path = resolver.resolve(metaDir, 100L, 1L, FileType.JSON).get(0);
    assertEquals(path, metaDir.resolve("00000000000000000100.json"));

    List<Path> paths = resolver.resolve(metaDir, 20L, 2L, FileType.PARQUET);
    assertEquals(paths, Arrays.asList(metaDir.resolve("00000000000000000020.checkpoint.0000000001.0000000002.parquet"),
      metaDir.resolve("00000000000000000020.checkpoint.0000000002.0000000002.parquet")));

  }

}
