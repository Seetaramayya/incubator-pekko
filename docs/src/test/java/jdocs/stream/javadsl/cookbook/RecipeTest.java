/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.javadsl.cookbook;

import jdocs.AbstractJavaTest;

public abstract class RecipeTest extends AbstractJavaTest {
  final class Message {
    public final String msg;

    public Message(String msg) {
      this.msg = msg;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      Message message = (Message) o;

      if (msg != null ? !msg.equals(message.msg) : message.msg != null) {
        return false;
      }

      return true;
    }

    @Override
    public int hashCode() {
      return msg != null ? msg.hashCode() : 0;
    }
  }
}
