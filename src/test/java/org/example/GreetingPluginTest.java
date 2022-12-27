package org.example;

import static org.gradle.internal.impldep.org.junit.Assert.assertTrue;

import org.gradle.api.Project;
import org.gradle.internal.impldep.org.junit.Test;
import org.gradle.testfixtures.ProjectBuilder;

public class GreetingPluginTest {
    @Test
    public void greeterPluginAddsGreetingTaskToProject() {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("cash.bdo.scalroid");

        assertTrue(project.getTasks().getByName("testToFile") instanceof GreetingToFileTask);
    }
}
