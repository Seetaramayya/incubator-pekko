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

package org.apache.pekko.actor

import scala.util.control.NoStackTrace

import org.apache.pekko
import pekko.actor.SupervisorStrategy.{ Restart, Stop }
import pekko.dispatch.sysmsg.SystemMessage
import pekko.event.EventStream
import pekko.testkit.{ PekkoSpec, TestProbe }

object UidClashTest {

  class TerminatedForNonWatchedActor
      extends Exception("Received Terminated for actor that was not actually watched")
      with NoStackTrace

  @volatile var oldActor: ActorRef = _

  private[pekko] class EvilCollidingActorRef(
      override val provider: ActorRefProvider,
      override val path: ActorPath,
      val eventStream: EventStream)
      extends MinimalActorRef {

    // Ignore everything
    override def isTerminated: Boolean = true
    override def sendSystemMessage(message: SystemMessage): Unit = ()
    override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = ()
  }

  def createCollidingRef(system: ActorSystem): ActorRef =
    new EvilCollidingActorRef(system.asInstanceOf[ActorSystemImpl].provider, oldActor.path, system.eventStream)

  case object PleaseRestart
  case object PingMyself
  case object RestartedSafely

  class RestartedActor extends Actor {

    def receive = {
      case PleaseRestart => throw new Exception("restart")
      case Terminated(_) => throw new TerminatedForNonWatchedActor
      // This is the tricky part to make this test a positive one (avoid expectNoMessage).
      // Since anything enqueued in postRestart will arrive before the Terminated
      // the bug triggers, there needs to be a bounce:
      // 1. Ping is sent from postRestart to self
      // 2. As a response to pint, RestartedSafely is sent to self
      // 3a. if Terminated was enqueued during the restart procedure it will arrive before the RestartedSafely message
      // 3b. otherwise only the RestartedSafely message arrives
      case PingMyself      => self ! RestartedSafely
      case RestartedSafely => context.parent ! RestartedSafely
    }

    override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
      context.children.foreach { child =>
        oldActor = child
        context.unwatch(child)
        context.stop(child)
      }
    }

    override def preStart(): Unit = context.watch(context.actorOf(Props.empty, "child"))

    override def postRestart(reason: Throwable): Unit = {
      context.watch(createCollidingRef(context.system))
      self ! PingMyself
    } // Simulate UID clash
  }

  class RestartingActor(probe: ActorRef) extends Actor {
    override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
      case _: TerminatedForNonWatchedActor =>
        context.stop(self)
        Stop
      case _ => Restart
    }
    val theRestartedOne = context.actorOf(Props[RestartedActor](), "theRestartedOne")

    def receive = {
      case PleaseRestart   => theRestartedOne ! PleaseRestart
      case RestartedSafely => probe ! RestartedSafely
    }
  }

}

class UidClashTest extends PekkoSpec {
  import UidClashTest._

  "The Terminated message for an old child stopped in preRestart" should {
    "not arrive after restart" in {
      val watcher = TestProbe()
      val topActor = system.actorOf(Props(classOf[RestartingActor], watcher.ref), "top")
      watcher.watch(topActor)

      topActor ! PleaseRestart
      watcher.expectMsg(RestartedSafely)
    }
  }

}
