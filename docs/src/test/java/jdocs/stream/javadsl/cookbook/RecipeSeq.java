/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2015-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.javadsl.cookbook;

import org.apache.pekko.NotUsed;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.stream.javadsl.Sink;
import org.apache.pekko.stream.javadsl.Source;
import org.apache.pekko.testkit.javadsl.TestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class RecipeSeq extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeLoggingElements");
  }

  @AfterClass
  public static void tearDown() {
    TestKit.shutdownActorSystem(system);
    system = null;
  }

  @Test
  public void drainSourceToList() throws Exception {
    new TestKit(system) {
      {
        final Source<String, NotUsed> mySource = Source.from(Arrays.asList("1", "2", "3"));
        // #draining-to-list-unsafe
        // Dangerous: might produce a collection with 2 billion elements!
        final CompletionStage<List<String>> strings = mySource.runWith(Sink.seq(), system);
        // #draining-to-list-unsafe

        strings.toCompletableFuture().get(3, TimeUnit.SECONDS);
      }
    };
  }

  @Test
  public void drainSourceToListWithLimit() throws Exception {
    new TestKit(system) {
      {
        final Source<String, NotUsed> mySource = Source.from(Arrays.asList("1", "2", "3"));
        // #draining-to-list-safe
        final int MAX_ALLOWED_SIZE = 100;

        // OK. Future will fail with a `StreamLimitReachedException`
        // if the number of incoming elements is larger than max
        final CompletionStage<List<String>> strings =
            mySource.limit(MAX_ALLOWED_SIZE).runWith(Sink.seq(), system);
        // #draining-to-list-safe

        strings.toCompletableFuture().get(1, TimeUnit.SECONDS);
      }
    };
  }

  public void drainSourceToListWithTake() throws Exception {
    new TestKit(system) {
      {
        final Source<String, NotUsed> mySource = Source.from(Arrays.asList("1", "2", "3"));
        final int MAX_ALLOWED_SIZE = 100;

        // #draining-to-list-safe

        // OK. Collect up until max-th elements only, then cancel upstream
        final CompletionStage<List<String>> strings =
            mySource.take(MAX_ALLOWED_SIZE).runWith(Sink.seq(), system);
        // #draining-to-list-safe

        strings.toCompletableFuture().get(1, TimeUnit.SECONDS);
      }
    };
  }
}
