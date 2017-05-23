/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.modules;

import java.io.File;
import java.io.FilePermission;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.file.*;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import org.jboss.modules.filter.PathFilter;
import org.jboss.modules.filter.PathFilters;
import org.jboss.modules.xml.ModuleXmlParser;

import static java.security.AccessController.doPrivileged;
import static org.jboss.modules.Utils.MODULE_FILE;

/**
 * A module finder which locates module specifications which are stored in a local module
 * repository on the filesystem, which uses {@code module.xml} descriptors.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
public final class LocalModuleFinder implements ModuleFinder {

    private static final File[] NO_FILES = new File[0];

    private final File[] repoRoots;
    private final PathFilter pathFilter;
    private final AccessControlContext accessControlContext;

    private LocalModuleFinder(final File[] repoRoots, final PathFilter pathFilter, final boolean cloneRoots) {
        this.repoRoots = cloneRoots && repoRoots.length > 0 ? repoRoots.clone() : repoRoots;
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            for (File repoRoot : this.repoRoots) {
                if (repoRoot == null) sm.checkPermission(new FilePermission(new File(repoRoot, "-").getPath(), "read"));
            }
        }
        this.pathFilter = pathFilter;
        this.accessControlContext = AccessController.getContext();
    }

    /**
     * Construct a new instance.
     *
     * @param repoRoots the repository roots to use
     * @param pathFilter the path filter to use
     */
    public LocalModuleFinder(final File[] repoRoots, final PathFilter pathFilter) {
        this(repoRoots, pathFilter, true);
    }

    /**
     * Construct a new instance.
     *
     * @param repoRoots the repository roots to use
     */
    public LocalModuleFinder(final File[] repoRoots) {
        this(repoRoots, PathFilters.acceptAll());
    }

    /**
     * Construct a new instance, using the {@code module.path} system property or the {@code JAVA_MODULEPATH} environment variable
     * to get the list of module repository roots.
     * <p>
     * This is equivalent to a call to {@link LocalModuleFinder#LocalModuleFinder(boolean) LocalModuleFinder(true)}.
     * </p>
     */
    public LocalModuleFinder() {
        this(true);
    }

    /**
     * Construct a new instance, using the {@code module.path} system property or the {@code JAVA_MODULEPATH} environment variable
     * to get the list of module repository roots.
     *
     * @param supportLayersAndAddOns {@code true} if the identified module repository roots should be checked for
     *                               an internal structure of child "layer" and "add-on" directories that may also
     *                               be treated as module roots lower in precedence than the parent root. Any "layers"
     *                               subdirectories whose names are specified in a {@code layers.conf} file found in
     *                               the module repository root will be added in the precedence of order specified
     *                               in the {@code layers.conf} file; all "add-on" subdirectories will be added at
     *                               a lower precedence than all "layers" and with no guaranteed precedence order
     *                               between them. If {@code false} no check for "layer" and "add-on" directories
     *                               will be performed.
     *
     */
    public LocalModuleFinder(boolean supportLayersAndAddOns) {
        this(getRepoRoots(supportLayersAndAddOns), PathFilters.acceptAll(), false);
    }

    static File[] getRepoRoots(final boolean supportLayersAndAddOns) {
        return supportLayersAndAddOns ? LayeredModulePathFactory.resolveLayeredModulePath(getModulePathFiles()) : getModulePathFiles();
    }

    private static File[] getModulePathFiles() {
        return getFiles(System.getProperty("module.path", System.getenv("JAVA_MODULEPATH")), 0, 0);
    }

    private static File[] getFiles(final String modulePath, final int stringIdx, final int arrayIdx) {
        if (modulePath == null) return NO_FILES;
        final int i = modulePath.indexOf(File.pathSeparatorChar, stringIdx);
        final File[] files;
        if (i == -1) {
            files = new File[arrayIdx + 1];
            files[arrayIdx] = new File(modulePath.substring(stringIdx)).getAbsoluteFile();
        } else {
            files = getFiles(modulePath, i + 1, arrayIdx + 1);
            files[arrayIdx] = new File(modulePath.substring(stringIdx, i)).getAbsoluteFile();
        }
        return files;
    }

    private static String toPathString(final String moduleName) {
        return moduleName.replace('.', File.separatorChar);
    }

    private static String toLegacyPathString(final String moduleName) {
        final ModuleIdentifier moduleIdentifier = ModuleIdentifier.fromString(moduleName);
        final String name = moduleIdentifier.getName();
        final String slot = moduleIdentifier.getSlot();
        final StringBuilder builder = new StringBuilder(name.length() + slot.length() + 2);
        builder.append(name.replace('.', File.separatorChar));
        builder.append(File.separatorChar).append(slot);
        return builder.toString();
    }

    public ModuleSpec findModule(final String name, final ModuleLoader delegateLoader) throws ModuleLoadException {
        final String child1 = toPathString(name);
        final String child2 = toLegacyPathString(name);
        final PathFilter pathFilter = this.pathFilter;
        if (pathFilter.accept(child1 + "/") || pathFilter.accept(child2 + "/")) {
            try {
                return doPrivileged((PrivilegedExceptionAction<ModuleSpec>) () -> parseModuleXmlFile(name, delegateLoader, repoRoots), accessControlContext);
            } catch (PrivilegedActionException e) {
                try {
                    throw e.getCause();
                } catch (IOException e1) {
                    throw new ModuleLoadException(e1);
                } catch (RuntimeException | Error | ModuleLoadException e1) {
                    throw e1;
                } catch (Throwable t) {
                    throw new UndeclaredThrowableException(t);
                }
            }
        }
        return null;
    }

    /**
     * Parse a {@code module.xml} file and return the corresponding module specification.
     *
     * @param identifier the identifier to load
     * @param delegateLoader the delegate module loader to use for module specifications
     * @param roots the repository root paths to search
     * @return the module specification
     * @throws IOException if reading the module file failed
     * @throws ModuleLoadException if creating the module specification failed (e.g. due to a parse error)
     * @deprecated Use {@link #parseModuleXmlFile(String, ModuleLoader, File...)} instead.
     */
    @Deprecated
    public static ModuleSpec parseModuleXmlFile(final ModuleIdentifier identifier, final ModuleLoader delegateLoader, final File... roots) throws IOException, ModuleLoadException {
        return parseModuleXmlFile(identifier.toString(), delegateLoader, roots);
    }

    /**
     * Parse a {@code module.xml} file and return the corresponding module specification.
     *
     * @param name the name of the module to load
     * @param delegateLoader the delegate module loader to use for module specifications
     * @param roots the repository root paths to search
     * @return the module specification
     * @throws IOException if reading the module file failed
     * @throws ModuleLoadException if creating the module specification failed (e.g. due to a parse error)
     */
    public static ModuleSpec parseModuleXmlFile(final String name, final ModuleLoader delegateLoader, final File... roots) throws IOException, ModuleLoadException {
        final String child1 = toPathString(name);
        final String child2 = toLegacyPathString(name);
        for (File root : roots) {
            Path path = root.toPath().resolve(child2);
            Path moduleXml = path.resolve(MODULE_FILE);
            //System.out.println(path.toFile().getAbsolutePath());
            System.out.println("Checking: " + moduleXml.toFile().getAbsolutePath());

            try {
                moduleXml.getFileSystem().provider().checkAccess(moduleXml, AccessMode.READ);
            } catch (NoSuchFileException ignored) {
                //System.out.println("No such file: " + moduleXml.getFileName().toString());
                continue;
            } catch (AccessDeniedException denied) {
                System.out.println("ACCESS DENIED!");
                // Thread.sleep(100);
                moduleXml.getFileSystem().provider().checkAccess(moduleXml, AccessMode.READ);
            } catch (FileSystemException e) {
                System.out.println("FileSystemException: " + moduleXml.toFile().getAbsolutePath());
                e.printStackTrace();
                continue;
            } catch (IOException e) {
                //final UnixSystem unixSystem = new UnixSystem();
                //throw new ModuleNotFoundException(name + ": Reading as " + unixSystem.getUid() + " id " + unixSystem.getUid(), e);
                System.out.println("IOException" + moduleXml.toFile().getAbsolutePath());
                e.printStackTrace();
                continue;
            }

            System.out.println("Loading module: " + moduleXml.toFile().getAbsolutePath());
            final ModuleSpec spec = ModuleXmlParser.parseModuleXml(delegateLoader, name, path.toFile(), moduleXml.toFile());
            if (spec == null) break;
            return spec;

        }
        throw new ModuleNotFoundException(name + ": File \"" + MODULE_FILE + "\" does not exist in any roots");
    }

    public String toString() {
        final StringBuilder b = new StringBuilder();
        b.append("local module finder @").append(Integer.toHexString(hashCode())).append(" (roots: ");
        final int repoRootsLength = repoRoots.length;
        for (int i = 0; i < repoRootsLength; i++) {
            final File root = repoRoots[i];
            b.append(root);
            if (i != repoRootsLength - 1) {
                b.append(',');
            }
        }
        b.append(')');
        return b.toString();
    }
}
