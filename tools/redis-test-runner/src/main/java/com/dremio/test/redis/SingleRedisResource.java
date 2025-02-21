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
package com.dremio.test.redis;

import java.io.IOException;
import java.net.ServerSocket;

import redis.embedded.RedisServer;
import redis.embedded.exceptions.EmbeddedRedisException;

/**
 * A single redis server resource
 */
class SingleRedisResource extends AbstractRedisResource {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(SingleRedisResource.class);
  private RedisServer redisServer = null;
  private static int RETRY_WAIT_MS = 1000;
  private static int MAX_RETRY = 3;

  public SingleRedisResource() {
    setPort(getRandomPort());
  }

  private int getRandomPort() {
    // Get a random port
    int port = REDIS_PORT;
    try(ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      port = socket.getLocalPort();
    } catch (IOException e) {
      logger.warn("Could not get a radomPort for embedded redis. Continuing using default port {}", REDIS_PORT, e);
    }
    return port;
  }

  public SingleRedisResource(int port) {
    setPort(port);
  }

  @Override
  protected void before() throws Throwable {
    int retries = 0;
    do {
      try {
        startRedisServer();
        return;
      } catch (EmbeddedRedisException e) {
        retries++;
        if (retries == MAX_RETRY) {
          logger.error("Failed to start embedded redis server", e);
          throw e;
        }
        Thread.sleep(RETRY_WAIT_MS);
      }
    } while (retries < MAX_RETRY);
  }

  private void startRedisServer() throws Throwable {
    redisServer = new RedisServer(getPort());
    redisServer.start();
    super.before();
  }

  @Override
  protected void after() {
    super.after();

    if (redisServer != null && redisServer.isActive()) {
      redisServer.stop();
      redisServer = null;
    }
  }
}
