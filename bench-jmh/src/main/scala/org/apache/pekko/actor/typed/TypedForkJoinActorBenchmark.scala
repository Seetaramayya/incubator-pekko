/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.actor.typed

import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup

import org.apache.pekko
import pekko.actor.typed.scaladsl.AskPattern._

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Threads(1)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 10, time = 15, timeUnit = TimeUnit.SECONDS, batchSize = 1)
class TypedForkJoinActorBenchmark {
  import TypedBenchmarkActors._
  import TypedForkJoinActorBenchmark._

  @Param(Array("50"))
  var tpt = 0

  @Param(Array(coresStr)) // coresStr, cores2xStr, cores4xStr
  var threads = ""

  @Param(
    Array(
      "pekko.dispatch.UnboundedMailbox",
      "pekko.dispatch.SingleConsumerOnlyUnboundedMailbox",
      "pekko.actor.ManyToOneArrayMailbox",
      "pekko.actor.JCToolsMailbox"))
  var mailbox = ""

  implicit var system: ActorSystem[PingPongCommand] = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    pekko.actor.BenchmarkActors.requireRightNumberOfCores(cores)
    system = ActorSystem(
      TypedBenchmarkActors.benchmarkPingPongSupervisor(),
      "TypedForkJoinActorBenchmark",
      ConfigFactory.parseString(s"""
       pekko.actor {

         default-mailbox.mailbox-capacity = 512

         fjp-dispatcher {
           executor = "fork-join-executor"
           fork-join-executor {
             parallelism-min = $threads
             parallelism-factor = 1.0
             parallelism-max = $threads
           }
           throughput = $tpt
           mailbox-type = "$mailbox"
         }
       }
      """))
  }

  @Benchmark
  @OperationsPerInvocation(totalMessagesLessThanCores)
  def pingPongLessActorsThanCores(): Unit =
    runPingPongBench(messages, lessThanCoresActors, "fjp-dispatcher", tpt)

  @Benchmark
  @OperationsPerInvocation(totalMessagesSameAsCores)
  def pingPongSameNumberOfActorsAsCores(): Unit =
    runPingPongBench(messages, sameAsCoresActors, "fjp-dispatcher", tpt)

  @Benchmark
  @OperationsPerInvocation(totalMessagesMoreThanCores)
  def pingPongMoreActorsThanCores(): Unit =
    runPingPongBench(messages, moreThanCoresActors, "fjp-dispatcher", tpt)

  def runPingPongBench(numMessages: Int, numActors: Int, dispatcher: String, tpt: Int): Unit = {
    val response: Future[PingPongStarted] =
      system.ask[PingPongStarted](ref => StartPingPong(numMessages, numActors, dispatcher, tpt, timeout, ref))(
        timeout,
        system.scheduler)
    val started = Await.result(response, timeout)
    started.completedLatch.await(timeout.toSeconds, TimeUnit.SECONDS)
    printProgress(started.totalNumMessages, numActors, started.startNanoTime)
    system ! Stop
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }
}

object TypedForkJoinActorBenchmark {
  final val messages = 2000000 // messages per actor pair

  val timeout = 30.seconds

  // Constants because they are used in annotations
  // update according to cpu
  final val cores = 8
  final val coresStr = "8"
  final val cores2xStr = "16"
  final val cores4xStr = "24"

  final val twoActors = 2
  final val moreThanCoresActors = cores * 2
  final val lessThanCoresActors = cores / 2
  final val sameAsCoresActors = cores

  final val totalMessagesTwoActors = messages
  final val totalMessagesMoreThanCores = (moreThanCoresActors * messages) / 2
  final val totalMessagesLessThanCores = (lessThanCoresActors * messages) / 2
  final val totalMessagesSameAsCores = (sameAsCoresActors * messages) / 2

}
