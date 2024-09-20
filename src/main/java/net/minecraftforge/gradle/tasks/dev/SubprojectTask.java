package net.minecraftforge.gradle.tasks.dev;

import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.gradle.dev.FmlDevPlugin;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.Set;

public class SubprojectTask extends DefaultTask {
    @InputFile
    private DelayedFile buildFile;
    @Input
    private String tasks;
    private final LinkedList<Action<Project>> configureProject = new LinkedList<>();
    private Action<Task> configureTask;

    public SubprojectTask() {
        this.getOutputs().doNotCacheIf("Not Available", e -> true);
    }

    @TaskAction
    public void doTask() throws IOException {
        Project childProj = FmlDevPlugin.getProject(getBuildFile(), getProject());

        // configure the project
        for (Action<Project> act : configureProject) {
            if (act != null)
                act.execute(childProj);
        }

        for (String task : tasks.split(" ")) {
            Set<Task> list = childProj.getTasksByName(task, false);
            for (Task t : list) {
                if (configureTask != null)
                    configureTask.execute(t);
                Constants.executeTask(t);
            }
        }
    }

    public File getBuildFile() {
        return buildFile.call();
    }

    public void setBuildFile(DelayedFile buildFile) {
        this.buildFile = buildFile;
    }

    public String getTasks() {
        return tasks;
    }

    public void setTasks(String tasks) {
        this.tasks = tasks;
    }

    public void configureProject(Action<Project> action) {
        configureProject.add(action);
    }
}