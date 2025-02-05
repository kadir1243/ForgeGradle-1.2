package net.minecraftforge.gradle.tasks;

import com.google.common.io.ByteStreams;
import net.minecraftforge.gradle.*;
import net.minecraftforge.gradle.common.BasePlugin;
import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.delayed.DelayedFile;
import net.minecraftforge.srg2source.ast.RangeExtractor;
import net.minecraftforge.srg2source.util.io.FolderSupplier;
import net.minecraftforge.srg2source.util.io.InputSupplier;
import net.minecraftforge.srg2source.util.io.ZipInputSupplier;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import javax.inject.Inject;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ExtractS2SRangeTask extends DefaultTask {
    private final File buildDir = ProjectBuildDirHelper.getBuildDir(getProject());
    @InputFiles
    private FileCollection libs;
    private DelayedFile projectFile; // to get classpath from a subproject
    private String projectConfig; // Also for a subProject
    private boolean includeJar = false; //Include the 'jar' task for subproject.

    @Optional
    @OutputFile
    private DelayedFile excOutput;

    // stuff defined on the tasks..
    @InputFiles
    private final List<DelayedFile> in = new LinkedList<>();

    @OutputFile
    private DelayedFile rangeMap;

    private boolean allCached = false;
    private static final Pattern FILE_FROM = Pattern.compile("\\s+@\\|([\\w\\d/.]+)\\|.*$");
    private static final Pattern FILE_START = Pattern.compile("\\s*Class Start\\: ([\\w\\d.]+)$");

    @TaskAction
    public void doTask() throws IOException {
        List<File> ins = getIn();
        File rangemap = getRangeMap();

        InputSupplier inSup;

        if (ins.isEmpty())
            return; // no input.
        else if (ins.size() == 1) {
            // just 1 supplier.
            inSup = getInput(ins.get(0));
        } else {
            // multinput
            inSup = new SequencedInputSupplier();
            for (File f : ins) {
                ((SequencedInputSupplier) inSup).add(getInput(f));
            }
        }

        // cache
        inSup = cacheInputs(inSup, rangemap);

        if (rangemap.exists()) {
            if (allCached) {
                return;
            }

            List<String> files = inSup.gatherAll(".java");

            // read rangemap
            List<String> lines = Files.readAllLines(rangemap.toPath());
            {
                Iterator<String> it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();

                    Matcher match;
                    String fileMatch = null;
                    if (line.trim().startsWith("@")) {
                        match = FILE_FROM.matcher(line);
                        if (match.matches()) {
                            fileMatch = match.group(1).replace('\\', '/');
                        }
                    } else {
                        match = FILE_START.matcher(line);
                        if (match.matches()) {
                            fileMatch = match.group(1).replace('.', '/') + ".java";
                        }
                    }

                    if (fileMatch != null && files.contains(fileMatch)) {
                        it.remove();
                    }
                }
            }

            generateRangeMap(inSup, rangemap);

            lines.addAll(Files.readAllLines(rangemap.toPath()));
            Files.write(rangemap.toPath(), lines);
        } else {
            generateRangeMap(inSup, rangemap);
        }
    }

    private InputSupplier cacheInputs(InputSupplier input, File out) throws IOException {
        boolean outExists = out.exists();

        // read the cache
        File cacheFile = new File(out + ".inputCache");
        HashSet<CacheEntry> cache = readCache(cacheFile);

        // generate the cache
        List<String> strings = input.gatherAll(".java");
        HashSet<CacheEntry> genCache = new HashSet<>(strings.size());
        PredefInputSupplier predef = new PredefInputSupplier();
        for (String rel : strings) {
            File root = new File(input.getRoot(rel)).getCanonicalFile();

            InputStream fis = input.getInput(rel);
            byte[] array = ByteStreams.toByteArray(fis);
            fis.close();

            CacheEntry entry = new CacheEntry(rel, root, Constants.hash(array));
            genCache.add(entry);

            if (!outExists || !cache.contains(entry)) {
                predef.addFile(rel, root, array);
            }
        }

        if (!predef.isEmpty()) {
            writeCache(cacheFile, genCache);
        } else {
            allCached = true;
        }

        return predef;
    }

    private HashSet<CacheEntry> readCache(File cacheFile) throws IOException {
        if (!cacheFile.exists())
            return new HashSet<>(0);

        List<String> lines = Files.readAllLines(cacheFile.toPath());
        HashSet<CacheEntry> cache = new HashSet<>(lines.size());

        for (String s : lines) {
            String[] tokens = s.split(";");
            if (tokens.length != 3) {
                getLogger().warn("Corrupted input cache! {}", cacheFile);
                break;
            }
            cache.add(new CacheEntry(tokens[0], new File(tokens[1]), tokens[2]));
        }

        return cache;
    }

    private void writeCache(File cacheFile, Collection<CacheEntry> cache) throws IOException {
        if (cacheFile.exists())
            cacheFile.delete();

        cacheFile.getParentFile().mkdirs();
        cacheFile.createNewFile();

        BufferedWriter writer = Files.newBufferedWriter(cacheFile.toPath());
        for (CacheEntry e : cache) {
            writer.write(e.toString());
            writer.newLine();
        }

        writer.close();
    }

    private void generateRangeMap(InputSupplier inSup, File rangeMap) {
        RangeExtractor extractor = new RangeExtractor();
        extractor.addLibs(getLibs().getAsPath()).setSrc(inSup);

        boolean worked;
        try (PrintStream stream = new PrintStream(Constants.getTaskLogStream(buildDir, this.getName() + ".log"))) {
            extractor.setOutLogger(stream);

            worked = extractor.generateRangeMap(rangeMap);
        }

        if (!worked)
            throw new RuntimeException("RangeMap generation Failed!!!");
    }

    private InputSupplier getInput(File f) throws IOException {
        if (f.isDirectory())
            return new FolderSupplier(f);
        else if (f.getPath().endsWith(".jar") || f.getPath().endsWith(".zip")) {
            ZipInputSupplier supp = new ZipInputSupplier();
            supp.readZip(f);
            return supp;
        } else
            throw new IllegalArgumentException("Can only make suppliers out of directories and zips right now!");
    }

    public File getRangeMap() {
        return rangeMap.call();
    }

    public void setRangeMap(DelayedFile out) {
        this.rangeMap = out;
    }

    public File getExcOutput() {
        return excOutput == null ? null : excOutput.call();
    }

    public void setExcOutput(DelayedFile out) {
        this.excOutput = out;
    }

    @InputFiles
    public FileCollection getIns() {
        return createFileCollection(in);
    }

    private FileCollection createFileCollection(Object... paths) {
        return GradleVersionUtils.choose("5.3", () -> getProject().files(paths), () -> getInjectedObjectFactory().fileCollection().from(paths));
    }

    @Inject
    protected ObjectFactory getInjectedObjectFactory() {
        throw new IllegalStateException("must be injected");
    }

    public List<File> getIn() {
        List<File> files = new LinkedList<>();
        for (DelayedFile f : in)
            files.add(f.call());
        return files;
    }

    public void addIn(DelayedFile in) {
        this.in.add(in);
    }

    public FileCollection getLibs() {
        if (projectFile != null && libs == null) { // libs == null to avoid doing this any more than necessary..
            File buildscript = projectFile.call();
            if (!buildscript.exists())
                return null;

            Project proj = BasePlugin.getProject(buildscript, getProject());
            libs = proj.getConfigurations().getByName(projectConfig);

            if (includeJar) {
                AbstractArchiveTask jarTask = (AbstractArchiveTask) proj.getTasks().getByName(JavaPlugin.JAR_TASK_NAME);
                Constants.executeTask(jarTask, getLogger());
                File compiled = ArchiveTaskHelper.getArchivePath(jarTask);
                libs = createFileCollection(compiled, libs);

                if (getExcOutput() != null) {
                    extractExcInfo(compiled, getExcOutput());
                }
            }
        }

        return libs;
    }

    public void setLibs(FileCollection libs) {
        this.libs = libs;
    }

    public void setLibsFromProject(DelayedFile buildscript, String config, boolean includeJar) {
        this.projectFile = buildscript;
        this.projectConfig = config;
        this.includeJar = includeJar;
    }

    private static class CacheEntry {
        public final String path, hash;
        public final File root;

        public CacheEntry(String path, File root, String hash) throws IOException {
            this.path = path.replace('\\', '/');
            this.hash = hash;
            this.root = root.getCanonicalFile();
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((hash == null) ? 0 : hash.hashCode());
            result = prime * result + ((path == null) ? 0 : path.hashCode());
            result = prime * result + ((root == null) ? 0 : root.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            CacheEntry other = (CacheEntry) obj;
            if (hash == null) {
                if (other.hash != null)
                    return false;
            } else if (!hash.equals(other.hash))
                return false;
            if (path == null) {
                if (other.path != null)
                    return false;
            } else if (!path.equals(other.path))
                return false;
            if (root == null) {
                if (other.root != null)
                    return false;
            } else if (!root.getAbsolutePath().equals(other.root.getAbsolutePath()))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return path + ";" + root + ";" + hash;
        }
    }


    private void extractExcInfo(File compiled, File output) {
        try {
            if (output.exists())
                output.delete();

            output.getParentFile().mkdirs();
            output.createNewFile();

            BufferedWriter writer = Files.newBufferedWriter(output.toPath());
            try (ZipInputStream inJar = new ZipInputStream(new BufferedInputStream(Files.newInputStream(compiled.toPath())))) {
                while (true) {
                    ZipEntry entry = inJar.getNextEntry();

                    if (entry == null) break;

                    if (entry.isDirectory()) continue;

                    String entryName = entry.getName();
                    if (!entryName.endsWith(".class") || !entryName.startsWith("net/minecraft/"))
                        continue;

                    getLogger().debug("Processing {}", entryName);
                    byte[] data = new byte[4096];
                    ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();

                    int len;
                    do {
                        len = inJar.read(data);
                        if (len > 0) {
                            entryBuffer.write(data, 0, len);
                        }
                    } while (len != -1);

                    byte[] entryData = entryBuffer.toByteArray();

                    ClassReader cr = new ClassReader(entryData);
                    ClassVisitor ca = new GenerateMapClassAdapter(writer);
                    cr.accept(ca, 0);
                }
            }
            // ignore

            writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static class GenerateMapClassAdapter extends ClassVisitor {
        String className;
        BufferedWriter writer;

        public GenerateMapClassAdapter(BufferedWriter writer) {
            super(Opcodes.ASM5);
            this.writer = writer;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.className = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            if (name.equals("<clinit>"))
                return super.visitMethod(access, name, desc, signature, exceptions);

            String clsSig = this.className + "/" + name + desc;

            try {
                if ((access & Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC) {
                    writer.write(clsSig);
                    writer.write("=static");
                    writer.newLine();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return super.visitMethod(access, name, desc, signature, exceptions);
        }
    }
}