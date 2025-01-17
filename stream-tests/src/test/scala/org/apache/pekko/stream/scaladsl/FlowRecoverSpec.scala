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

package org.apache.pekko.stream.scaladsl

import scala.util.control.NoStackTrace

import org.apache.pekko
import pekko.stream.testkit.StreamSpec
import pekko.stream.testkit.scaladsl.TestSink
import pekko.testkit.EventFilter

class FlowRecoverSpec extends StreamSpec("""
    pekko.stream.materializer.initial-input-buffer-size = 1
    pekko.stream.materializer.max-input-buffer-size = 1
  """) {

  val ex = new RuntimeException("ex") with NoStackTrace

  "A Recover" must {
    "recover when there is a handler" in {
      Source(1 to 4)
        .map { a =>
          if (a == 3) throw ex else a
        }
        .recover { case _: Throwable => 0 }
        .runWith(TestSink.probe[Int])
        .requestNext(1)
        .requestNext(2)
        .requestNext(0)
        .request(1)
        .expectComplete()
    }

    "failed stream if handler is not for such exception type" in {
      Source(1 to 3)
        .map { a =>
          if (a == 2) throw ex else a
        }
        .recover { case _: IndexOutOfBoundsException => 0 }
        .runWith(TestSink.probe[Int])
        .requestNext(1)
        .request(1)
        .expectError(ex)
    }

    "not influence stream when there is no exceptions" in {
      Source(1 to 3)
        .map(identity)
        .recover { case _: Throwable => 0 }
        .runWith(TestSink.probe[Int])
        .request(3)
        .expectNextN(1 to 3)
        .expectComplete()
    }

    "finish stream if it's empty" in {
      Source.empty.recover { case _: Throwable => 0 }.runWith(TestSink.probe[Int]).request(1).expectComplete()
    }

    "not log error when exception is thrown from recover block" in {
      val ex = new IndexOutOfBoundsException("quite intuitive")
      EventFilter[IndexOutOfBoundsException](occurrences = 0).intercept {
        Source
          .failed(new IllegalStateException("expected illegal state"))
          .recover { case _: IllegalStateException => throw ex }
          .runWith(TestSink.probe[Int])
          .expectSubscriptionAndError(ex)
      }
    }
  }
}
