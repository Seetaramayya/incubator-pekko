/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.typed
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko
import pekko.Done
import pekko.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit }
import pekko.actor.typed.{ ActorRef, Behavior }
import pekko.persistence.testkit.{ PersistenceTestKitPlugin, PersistenceTestKitSnapshotPlugin }
import pekko.persistence.testkit.scaladsl.{ PersistenceTestKit, SnapshotTestKit }
import pekko.persistence.testkit.query.scaladsl.PersistenceTestKitReadJournal
import pekko.persistence.typed.internal.{ ReplicatedPublishedEventMetaData, VersionVector }
import pekko.persistence.typed.scaladsl.ReplicatedEventSourcing
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpecLike

object ReplicationSnapshotSpec {

  import ReplicatedEventSourcingSpec._

  val EntityType = "SnapshotSpec"

  def behaviorWithSnapshotting(entityId: String, replicaId: ReplicaId): Behavior[Command] =
    behaviorWithSnapshotting(entityId, replicaId, None)

  def behaviorWithSnapshotting(
      entityId: String,
      replicaId: ReplicaId,
      eventProbe: ActorRef[EventAndContext]): Behavior[Command] =
    behaviorWithSnapshotting(entityId, replicaId, Some(eventProbe))

  def behaviorWithSnapshotting(
      entityId: String,
      replicaId: ReplicaId,
      probe: Option[ActorRef[EventAndContext]]): Behavior[Command] = {
    ReplicatedEventSourcing.commonJournalConfig(
      ReplicationId(EntityType, entityId, replicaId),
      AllReplicas,
      PersistenceTestKitReadJournal.Identifier)(replicationContext =>
      eventSourcedBehavior(replicationContext, probe).snapshotWhen((_, _, sequenceNr) => sequenceNr % 2 == 0))

  }
}

class ReplicationSnapshotSpec
    extends ScalaTestWithActorTestKit(
      PersistenceTestKitPlugin.config.withFallback(PersistenceTestKitSnapshotPlugin.config))
    with AnyWordSpecLike
    with LogCapturing
    with Eventually {
  import ReplicatedEventSourcingSpec._
  import ReplicationSnapshotSpec._

  val ids = new AtomicInteger(0)
  def nextEntityId = s"e-${ids.getAndIncrement()}"

  val snapshotTestKit = SnapshotTestKit(system)
  val persistenceTestKit = PersistenceTestKit(system)

  val R1 = ReplicaId("R1")
  val R2 = ReplicaId("R2")

  "ReplicatedEventSourcing" should {
    "recover state from snapshots" in {
      val entityId = nextEntityId
      val persistenceIdR1 = s"$EntityType|$entityId|R1"
      val persistenceIdR2 = s"$EntityType|$entityId|R2"
      val probe = createTestProbe[Done]()
      val r2EventProbe = createTestProbe[EventAndContext]()

      {
        val r1 = spawn(behaviorWithSnapshotting(entityId, R1))
        val r2 = spawn(behaviorWithSnapshotting(entityId, R2, r2EventProbe.ref))
        r1 ! StoreMe("r1 1", probe.ref)
        r1 ! StoreMe("r1 2", probe.ref)
        r2EventProbe.expectMessageType[EventAndContext]
        r2EventProbe.expectMessageType[EventAndContext]

        snapshotTestKit.expectNextPersisted(persistenceIdR1, State(List("r1 2", "r1 1")))
        snapshotTestKit.expectNextPersisted(persistenceIdR2, State(List("r1 2", "r1 1")))

        r2.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
          ReplicationId(EntityType, entityId, R1).persistenceId,
          1L,
          "two-again",
          System.currentTimeMillis(),
          Some(new ReplicatedPublishedEventMetaData(R1, VersionVector.empty)))

        // r2 should now filter out that event if it receives it again
        r2EventProbe.expectNoMessage()
      }

      // restart r2 from a snapshot, the event should still be filtered
      {
        val r2 = spawn(behaviorWithSnapshotting(entityId, R2, r2EventProbe.ref))
        r2.asInstanceOf[ActorRef[Any]] ! internal.PublishedEventImpl(
          ReplicationId(EntityType, entityId, R1).persistenceId,
          1L,
          "two-again",
          System.currentTimeMillis(),
          Some(new ReplicatedPublishedEventMetaData(R1, VersionVector.empty)))
        r2EventProbe.expectNoMessage()

        val stateProbe = createTestProbe[State]()
        r2 ! GetState(stateProbe.ref)
        stateProbe.expectMessage(State(List("r1 2", "r1 1")))
      }
    }
  }
}
