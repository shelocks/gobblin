/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.writer.initializer;

import gobblin.initializer.Initializer;
import gobblin.initializer.MultiInitializer;

import java.util.List;

import lombok.ToString;

@ToString
public class MultiWriterInitializer implements WriterInitializer {

  private final Initializer intializer;

  public MultiWriterInitializer(List<WriterInitializer> writerInitializers) {
    intializer = new MultiInitializer(writerInitializers);
  }

  @Override
  public void initialize() {
    intializer.initialize();
  }

  @Override
  public void close() {
    intializer.close();
  }

}
