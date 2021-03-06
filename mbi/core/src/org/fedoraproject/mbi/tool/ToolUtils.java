/*-
 * Copyright (c) 2020 Red Hat, Inc.
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
package org.fedoraproject.mbi.tool;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import org.fedoraproject.mbi.Reactor;
import org.fedoraproject.mbi.model.Execution;
import org.fedoraproject.mbi.model.Instruction;
import org.fedoraproject.mbi.model.ModuleDescriptor;

/**
 * @author Mikolaj Izdebski
 */
public class ToolUtils
{
    // Binary semaphore to ensure there is at most one run of Ant per JVM process.
    private static final Semaphore ANT_SEMAPHORE = new Semaphore( 1 );

    public static URLClassLoader newClassLoader( Reactor reactor, ModuleDescriptor... modules )
    {
        try
        {
            List<URL> urls = new ArrayList<>();
            for ( ModuleDescriptor module : modules )
            {
                if ( module != null )
                {
                    urls.add( reactor.getClassesDir( module ).toUri().toURL() );
                    for ( Path path : reactor.getClassPath( module ) )
                    {
                        urls.add( path.toUri().toURL() );
                    }
                }
            }
            return new URLClassLoader( urls.toArray( new URL[] {} ), ToolUtils.class.getClassLoader() );
        }
        catch ( MalformedURLException e )
        {
            throw new RuntimeException( e );
        }
    }

    public static void runToolOnProject( Reactor reactor, ModuleDescriptor toolModule, ModuleDescriptor module,
                                         Execution exec )
        throws Exception
    {
        Semaphore semaphore = exec.getToolName().equals( "ant" ) ? ANT_SEMAPHORE : new Semaphore( 1 );
        ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
        try ( URLClassLoader cl = ToolUtils.newClassLoader( reactor, toolModule, module ) )
        {
            Thread.currentThread().setContextClassLoader( cl );
            var entryClassName = Tool.class.getPackage().getName() + "." + exec.getToolName() + "."
                + exec.getToolName().substring( 0, 1 ).toUpperCase() + exec.getToolName().substring( 1 ) + "Tool";
            Tool tool = (Tool) cl.loadClass( entryClassName ).getConstructor().newInstance();
            tool.setReactor( reactor );
            tool.setModule( module );
            tool.initialize();
            for ( Instruction instruction : exec.getInstructions() )
            {
                tool.executeInstruction( instruction );
            }
            semaphore.acquireUninterruptibly();
            tool.execute();
        }
        finally
        {
            semaphore.release();
            Thread.currentThread().setContextClassLoader( oldCl );
        }
    }
}
