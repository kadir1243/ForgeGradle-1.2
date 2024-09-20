package net.minecraftforge.gradle.tasks.abstractutil;

import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.java.archives.Manifest;
import org.gradle.api.tasks.bundling.Jar;

public class DelayedJar extends Jar {
    private Action<? super Manifest> closure = null;

    @Override
    public void copy() {
        if (closure != null) {
            super.manifest(closure);
        }
        super.copy();
    }

    public void setManifest(Closure<?> closure) {
        this.closure = new Action<Manifest>() {
            @Override
            public void execute(Manifest manifest) {
                closure.call(manifest);
            }
        };
    }

    public void setManifest(Action<Manifest> action) {
        this.closure = action;
    }
}
