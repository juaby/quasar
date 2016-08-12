/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (c) 2013-2016, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.fibers.instrument;

import co.paralleluniverse.common.util.Debug;
import co.paralleluniverse.common.util.SystemProperties;
import co.paralleluniverse.common.util.VisibleForTesting;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Date;
import java.util.WeakHashMap;

/**
 * @author pron
 */
public final class QuasarInstrumentor {
    @SuppressWarnings("WeakerAccess")
    public static final int ASMAPI = Opcodes.ASM6;

    /* private */ final static String EXAMINED_CLASS = System.getProperty("co.paralleluniverse.fibers.writeInstrumentedClassesStartingWith");
    private static final boolean allowJdkInstrumentation = SystemProperties.isEmptyOrTrue("co.paralleluniverse.fibers.allowJdkInstrumentation");
    private WeakHashMap<ClassLoader, MethodDatabase> dbForClassloader = new WeakHashMap<>();
    private boolean check;
    private final boolean aot;
    private boolean allowMonitors;
    private boolean allowBlocking;
    private Log log;
    private boolean verbose;
    private boolean debug;
    private int logLevelMask;

    public QuasarInstrumentor() {
        this(false);
    }

    public QuasarInstrumentor(boolean aot) {
        this.aot = aot;
        setLogLevelMask();
    }

    @SuppressWarnings("unused")
    public boolean isAOT() {
        return aot;
    }

    @SuppressWarnings("WeakerAccess")
    public boolean shouldInstrument(String className) {
        if (className != null) {
            className = className.replace('.', '/');
            return
                !(className.startsWith("co/paralleluniverse/fibers/instrument/") &&
                    !Debug.isUnitTest()) &&
                    !(className.equals(Classes.FIBER_CLASS_NAME) ||
                        className.startsWith(Classes.FIBER_CLASS_NAME + '$')) &&
                    !className.equals(Classes.STACK_NAME) &&
                    !className.startsWith("org/objectweb/asm/") &&
                    !className.startsWith("org/netbeans/lib/") &&
                    !(className.startsWith("java/lang/") ||
                        (!allowJdkInstrumentation && MethodDatabase.isJDK(className)));
        }
        return true;
    }

    @SuppressWarnings("WeakerAccess")
    public byte[] instrumentClass(ClassLoader loader, String className, byte[] data) throws IOException {
        try (final InputStream is = new ByteArrayInputStream(data)) {
            return shouldInstrument(className) ? instrumentClass(loader, className, is, false) : data;
        }
    }

    @SuppressWarnings("WeakerAccess")
    public byte[] instrumentClass(ClassLoader loader, String className, InputStream is) throws IOException {
        return instrumentClass(loader, className, is, false);
    }

    @VisibleForTesting
    byte[] instrumentClass(ClassLoader loader, String className, InputStream is, boolean forceInstrumentation) throws IOException {
        className = className != null ? className.replace('.', '/') : null;

        byte[] cb = toByteArray(is);

        MethodDatabase db = getMethodDatabase(loader);

        if (className != null) {
            log(LogLevel.INFO, "TRANSFORM: %s %s", className,
                (db.getClassEntry(className) != null && db.getClassEntry(className).requiresInstrumentation()) ? "request" : "");

            // DEBUG
            if (EXAMINED_CLASS != null && className.startsWith(EXAMINED_CLASS)) {
                writeToFile(className.replace('/', '.') + "-" + new Date().getTime() + "-quasar-1-preinstr.class", cb);
                // cv1 = new TraceClassVisitor(cv, new PrintWriter(System.err));
            }
        } else {
            log(LogLevel.INFO, "TRANSFORM: null className");
        }

        // Phase 1, add a label before any suspendable calls, event API is enough
        final ClassReader r1 = new ClassReader(cb);
        final ClassWriter cw1 = new ClassWriter(r1, 0);
        final LabelSuspendableCallSitesClassVisitor ic1 = new LabelSuspendableCallSitesClassVisitor(cw1, db);
        r1.accept(ic1, 0);
        cb = cw1.toByteArray();

        // DEBUG
        if (EXAMINED_CLASS != null && className != null && className.startsWith(EXAMINED_CLASS)) {
            writeToFile(className.replace('/', '.') + "-" + new Date().getTime() + "-quasar-2.class", cb);
            // cv1 = new TraceClassVisitor(cv, new PrintWriter(System.err));
        }

        // Phase 2, record suspendable call offsets pre-instrumentation, event API is enough (read-only)
        final OffsetClassReader r2 = new OffsetClassReader(cb);
        final ClassWriter cw2 = new ClassWriter(r2, 0);
        final SuspOffsetsBeforeInstrClassVisitor ic2 = new SuspOffsetsBeforeInstrClassVisitor(cw2, db);
        r2.accept(ic2, 0);
        cb = cw2.toByteArray(); // Class not really touched, just convenience

        // DEBUG
        if (EXAMINED_CLASS != null && className != null && className.startsWith(EXAMINED_CLASS)) {
            writeToFile(className.replace('/', '.') + "-" + new Date().getTime() + "-quasar-3.class", cb);
            // cv1 = new TraceClassVisitor(cv, new PrintWriter(System.err));
        }

        // Phase 3, instrument, tree API
        final ClassReader r3 = new ClassReader(cb);
        final ClassWriter cw3 = new DBClassWriter(db, r3);
        final ClassVisitor cv3 = (check && EXAMINED_CLASS == null) ? new CheckClassAdapter(cw3) : cw3;
        final InstrumentClassVisitor ic3 = new InstrumentClassVisitor(cv3, db, forceInstrumentation, aot);
        try {
            r3.accept(ic3, ClassReader.SKIP_FRAMES);
            cb = cw3.toByteArray();
        } catch (final Exception e) {
            if (ic3.hasSuspendableMethods()) {
                error("Unable to instrument class " + className, e);
                throw e;
            } else {
                if (!MethodDatabase.isProblematicClass(className))
                    log(LogLevel.DEBUG, "Unable to instrument class " + className);
                return null;
            }
        }

        // DEBUG
        if (EXAMINED_CLASS != null && className != null && className.startsWith(EXAMINED_CLASS)) {
            writeToFile(className.replace('/', '.') + "-" + new Date().getTime() + "-quasar-4.class", cb);
            // cv1 = new TraceClassVisitor(cv, new PrintWriter(System.err));
        }

        // Phase 4, fill suspendable call offsets, event API is enough
        final OffsetClassReader r4 = new OffsetClassReader(cb);
        final ClassWriter cw4 = new ClassWriter(r4, 0);
        final SuspOffsetsAfterInstrClassVisitor ic4 = new SuspOffsetsAfterInstrClassVisitor(cw4, db);
        r4.accept(ic4, 0);
        cb = cw4.toByteArray();

        // DEBUG
        if (EXAMINED_CLASS != null) {
            if (className != null && className.startsWith(EXAMINED_CLASS))
                writeToFile(className.replace('/', '.') + "-" + new Date().getTime() + "-quasar-5-final.class", cb);

            if (check) {
                ClassReader r5 = new ClassReader(cb);
                ClassVisitor cv5 = new CheckClassAdapter(new TraceClassVisitor(null), true);
                r5.accept(cv5, 0);
            }
        }

        return cb;
    }

    @SuppressWarnings("WeakerAccess")
    public synchronized MethodDatabase getMethodDatabase(ClassLoader loader) {
        if (loader == null)
            throw new IllegalArgumentException();
        if (!dbForClassloader.containsKey(loader)) {
            MethodDatabase newDb = new MethodDatabase(this, loader, new DefaultSuspendableClassifier(loader));
            dbForClassloader.put(loader, newDb);
            return newDb;
        } else
            return dbForClassloader.get(loader);
    }

    public QuasarInstrumentor setCheck(boolean check) {
        this.check = check;
        return this;
    }

    @SuppressWarnings("WeakerAccess")
    public synchronized boolean isAllowMonitors() {
        return allowMonitors;
    }

    @SuppressWarnings("WeakerAccess")
    public synchronized QuasarInstrumentor setAllowMonitors(boolean allowMonitors) {
        this.allowMonitors = allowMonitors;
        return this;
    }

    @SuppressWarnings("WeakerAccess")
    public synchronized boolean isAllowBlocking() {
        return allowBlocking;
    }

    @SuppressWarnings("WeakerAccess")
    public synchronized QuasarInstrumentor setAllowBlocking(boolean allowBlocking) {
        this.allowBlocking = allowBlocking;
        return this;
    }

    public synchronized QuasarInstrumentor setLog(Log log) {
        this.log = log;
//        for (MethodDatabase db : dbForClassloader.values()) {
//            db.setLog(log);
//        }
        return this;
    }

    public Log getLog() {
        return log;
    }

    @SuppressWarnings("WeakerAccess")
    public synchronized boolean isVerbose() {
        return verbose;
    }

    @SuppressWarnings("WeakerAccess")
    public synchronized void setVerbose(boolean verbose) {
        this.verbose = verbose;
        setLogLevelMask();
    }

    public synchronized boolean isDebug() {
        return debug;
    }

    public synchronized void setDebug(boolean debug) {
        this.debug = debug;
        setLogLevelMask();
    }

    private synchronized void setLogLevelMask() {
        logLevelMask = (1 << LogLevel.WARNING.ordinal());
        if (verbose || debug)
            logLevelMask |= (1 << LogLevel.INFO.ordinal());
        if (debug)
            logLevelMask |= (1 << LogLevel.DEBUG.ordinal());
    }

    public void log(LogLevel level, String msg, Object... args) {
        if (log != null && (logLevelMask & (1 << level.ordinal())) != 0)
            log.log(level, msg, args);
    }

    public void error(String msg, Throwable ex) {
        if (log != null)
            log.error(msg, ex);
    }

    String checkClass(ClassLoader cl, File f) {
        return getMethodDatabase(cl).checkClass(f);
    }

    /* private */ static void writeToFile(String name, byte[] data) {
        try (OutputStream os = Files.newOutputStream(Paths.get(name), StandardOpenOption.CREATE_NEW)) {
            os.write(data);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] toByteArray(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static final int BUF_SIZE = 8192;

    private static long copy(InputStream from, OutputStream to)
        throws IOException {
        byte[] buf = new byte[BUF_SIZE];
        long total = 0;
        while (true) {
            int r = from.read(buf);
            if (r == -1) {
                break;
            }
            to.write(buf, 0, r);
            total += r;
        }
        return total;
    }
}
