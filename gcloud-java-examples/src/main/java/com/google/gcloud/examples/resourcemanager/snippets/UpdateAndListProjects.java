/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * EDITING INSTRUCTIONS
 * This file is referenced in READMEs and javadoc. Any change to this file should be reflected in
 * the project's READMEs and package-info.java.
 */

package com.google.gcloud.examples.resourcemanager.snippets;

import com.google.gcloud.resourcemanager.Project;
import com.google.gcloud.resourcemanager.ResourceManager;
import com.google.gcloud.resourcemanager.ResourceManagerOptions;

import java.util.Iterator;

/**
 * A snippet for Google Cloud Resource Manager showing how to update a project and list all projects
 * the user has permission to view.
 */
public class UpdateAndListProjects {

  public static void main(String... args) {
    // Create Resource Manager service object
    // By default, credentials are inferred from the runtime environment.
    ResourceManager resourceManager = ResourceManagerOptions.defaultInstance().service();

    // Get a project from the server
    Project project = resourceManager.get("some-project-id"); // Use an existing project's ID

    // Update a project
    if (project != null) {
      Project newProject = project.toBuilder()
          .addLabel("launch-status", "in-development")
          .build()
          .replace();
      System.out.println("Updated the labels of project " + newProject.projectId()
          + " to be " + newProject.labels());
    }

    // List all the projects you have permission to view.
    Iterator<Project> projectIterator = resourceManager.list().iterateAll();
    System.out.println("Projects I can view:");
    while (projectIterator.hasNext()) {
      System.out.println(projectIterator.next().projectId());
    }
  }
}
