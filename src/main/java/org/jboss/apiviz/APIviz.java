/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
 * by the @author tags. See the COPYRIGHT.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.apiviz;

import static org.jboss.apiviz.Constant.NEWLINE;
import static org.jboss.apiviz.Constant.OPTION_CATEGORY;
import static org.jboss.apiviz.Constant.OPTION_HELP;
import static org.jboss.apiviz.Constant.OPTION_NO_PACKAGE_DIAGRAM;
import static org.jboss.apiviz.Constant.OPTION_SOURCE_CLASS_PATH;
import static org.jboss.apiviz.Constant.TAG_CATEGORY;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdepend.framework.JDepend;
import jdepend.framework.JavaClass;
import jdepend.framework.JavaPackage;
import jdepend.framework.PackageFilter;

import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.sun.javadoc.ClassDoc;
import com.sun.javadoc.DocErrorReporter;
import com.sun.javadoc.LanguageVersion;
import com.sun.javadoc.PackageDoc;
import com.sun.javadoc.RootDoc;
import com.sun.tools.doclets.formats.html.ConfigurationImpl;
import com.sun.tools.doclets.formats.html.LinkInfoImpl;
import com.sun.tools.doclets.formats.html.NestedClassWriterImpl;
import com.sun.tools.doclets.formats.html.SubWriterHolderWriter;
import com.sun.tools.doclets.formats.html.WriterFactoryImpl;
import com.sun.tools.doclets.formats.html.markup.HtmlConstants;
import com.sun.tools.doclets.formats.html.markup.HtmlTree;
import com.sun.tools.doclets.formats.html.markup.RawHtml;
import com.sun.tools.doclets.formats.html.markup.StringContent;
import com.sun.tools.doclets.internal.toolkit.ClassWriter;
import com.sun.tools.doclets.internal.toolkit.Content;
import com.sun.tools.doclets.internal.toolkit.MemberSummaryWriter;
import com.sun.tools.doclets.internal.toolkit.util.VisibleMemberMap;
import com.sun.tools.doclets.standard.Standard;

/**
 * @author The APIviz Project (apiviz-dev@lists.jboss.org)
 * @author Trustin Lee (tlee@redhat.com)
 *
 * @version $Rev: 84 $, $Date: 2010-03-04 09:08:41 +0100 (Thu, 04 Mar 2010) $
 *
 */
public class APIviz {

    private static final Pattern INSERTION_POINT_PATTERN = Pattern.compile(
            "((<\\/PRE>)(?=\\s*<P>)|(?=<TABLE BORDER=\"1\")|(?=<div class=\"contentContainer\"))");

    static Map<String, PackageDoc> getPackages(RootDoc root) {
        Map<String, PackageDoc> packages = new TreeMap<String, PackageDoc>();
        for (ClassDoc c: root.classes()) {
            PackageDoc p = c.containingPackage();
            if(!packages.containsKey(p.name())) {
                packages.put(p.name(), p);
            }
        }

        return packages;
    }

    @SuppressWarnings("unchecked")
    private static boolean checkClasspathOption(RootDoc root, JDepend jdepend) {
        // Sanity check
        boolean correctClasspath = true;
        if (jdepend.countClasses() == 0) {
            root.printWarning(
                    "JDepend was not able to locate any compiled class files.");
            correctClasspath = false;
        } else {
            for (ClassDoc c: root.classes()) {
                if (c.containingPackage() == null ||
                    c.containingPackage().name() == null ||
                    ClassDocGraph.isHidden(c.containingPackage())) {
                    continue;
                }

                boolean found = false;
                String fqcn = c.containingPackage().name() + '.' + c.name().replace('.', '$');
                JavaPackage jpkg = jdepend.getPackage(c.containingPackage().name());
                if (jpkg != null) {
                    Collection<JavaClass> jclasses = jpkg.getClasses();
                    if (jclasses != null) {
                        for (JavaClass jcls: jclasses) {
                            if (fqcn.equals(jcls.getName())) {
                                found = true;
                                break;
                            }
                        }
                    }
                }

                if (!found) {
                    root.printWarning(
                            "JDepend was not able to locate some compiled class files: " + fqcn);
                    correctClasspath = false;
                    break;
                }
            }
        }
        return correctClasspath;
    }

    private static void generateClassDiagrams(RootDoc root, ClassDocGraph graph, File outputDirectory) throws IOException {
        for (ClassDoc c: root.classes()) {
            if (c.containingPackage() == null) {
                instrumentDiagram(
                        root, outputDirectory,
                        c.name(),
                        graph.getClassDiagram(c));
            } else {
                instrumentDiagram(
                        root, outputDirectory,
                        c.containingPackage().name().replace('.', File.separatorChar) +
                        File.separatorChar + c.name(),
                        graph.getClassDiagram(c));
            }
        }
    }

    private static void generateOverviewSummary(RootDoc root, ClassDocGraph graph, File outputDirectory) throws IOException {
        final Map<String, PackageDoc> packages = getPackages(root);
        PackageFilter packageFilter = new PackageFilter() {
            @Override
            public boolean accept(String packageName) {
                PackageDoc p = packages.get(packageName);
                if (p == null) {
                    return false;
                }

                return !ClassDocGraph.isHidden(p);
            }
        };

        JDepend jdepend = new JDepend(packageFilter);

        File[] classPath = getClassPath(root.options());
        for (File e: classPath) {
            if (e.isDirectory()) {
                root.printNotice(
                        "Included into dependency analysis: " + e);
                jdepend.addDirectory(e.toString());
            } else {
                root.printNotice(
                        "Excluded from dependency analysis: " + e);
            }
        }

        jdepend.analyze();

        if (checkClasspathOption(root, jdepend)) {
            instrumentDiagram(
                    root, outputDirectory, "overview-summary",
                    graph.getOverviewSummaryDiagram(jdepend));
        } else {
            root.printWarning(
                    "Please make sure that the '" +
                    OPTION_SOURCE_CLASS_PATH +
                    "' option was specified correctly.");
            root.printWarning(
                    "Package dependency diagram will not be generated " +
                    "to avoid the inaccurate result.");
        }
    }

    private static void generatePackageSummaries(RootDoc root, ClassDocGraph graph, File outputDirectory) throws IOException {
        for (PackageDoc p: getPackages(root).values()) {
            instrumentDiagram(
                    root, outputDirectory,
                    p.name().replace('.', File.separatorChar) +
                    File.separatorChar + "package-summary",
                    graph.getPackageSummaryDiagram(p));
        }
    }

    private static File[] getClassPath(String[][] options) {
        Set<File> cp = new LinkedHashSet<File>();

        for (String[] o: options) {
            if (o[0].equals(OPTION_SOURCE_CLASS_PATH)) {
                String[] cps = o[1].split(File.pathSeparator);
                for (String p : cps) {
                    cp.add(new File(p));
                }
            }
        }

        for (String[] o: options) {
            if (o[0].equals("-classpath")) {
                String[] cps = o[1].split(File.pathSeparator);
                for (String p : cps) {
                    cp.add(new File(p));
                }
            }
        }

        return cp.toArray(new File[cp.size()]);
    }

    private static File getOutputDirectory(String[][] options) {
        for (String[] o: options) {
            if (o[0].equals("-d")) {
                return new File(o[1]);
            }
        }

        // Fall back to the current working directory.
        return new File(System.getProperty("user.dir", "."));
    }

    private static void instrumentDiagram(RootDoc root, File outputDirectory, String filename, String diagram) throws IOException {
        // TODO - it would be nice to have a debug flag that would spit out the graphviz source as well
        //System.out.println(diagram);

        boolean needsBottomMargin = filename.contains("overview-summary") || filename.contains("package-summary");

        File htmlFile = new File(outputDirectory, filename + ".html");
        File pngFile = new File(outputDirectory, filename + ".png");
        File mapFile = new File(outputDirectory, filename + ".map");

        if (!htmlFile.exists()) {
            // Shouldn't reach here anymore.
            // I'm retaining the code just in case.
            for (;;) {
                int idx = filename.lastIndexOf(File.separatorChar);
                if (idx > 0) {
                    filename = filename.substring(0, idx) + '.' +
                               filename.substring(idx + 1);
                } else {
                    // Give up (maybe missing)
                    return;
                }
                htmlFile = new File(outputDirectory, filename + ".html");
                if (htmlFile.exists()) {
                    pngFile = new File(outputDirectory, filename + ".png");
                    mapFile = new File(outputDirectory, filename + ".map");
                    break;
                }
            }
        }

        root.printNotice("Generating " + pngFile + "...");
        Graphviz.writeImageAndMap(root, diagram, outputDirectory, filename);

        try {
            String oldContent = FileUtil.readFile(htmlFile);
            String mapContent = FileUtil.readFile(mapFile);

            Matcher matcher = INSERTION_POINT_PATTERN.matcher(oldContent);
            if (!matcher.find()) {
                throw new IllegalStateException(
                        "Failed to find an insertion point.");
            }
            String newContent =
                oldContent.substring(0, matcher.end()) +
                mapContent + NEWLINE +
                "<CENTER><IMG SRC=\"" + pngFile.getName() +
                "\" USEMAP=\"#APIVIZ\" BORDER=\"0\"></CENTER>" +
                NEWLINE +
                (needsBottomMargin? "<BR>" : "") +
                NEWLINE +
                oldContent.substring(matcher.end());
            FileUtil.writeFile(htmlFile, newContent);
        } finally {
            mapFile.delete();
        }
    }

    private static boolean shouldGeneratePackageDiagram(String[][] options) {
        for (String[] o: options) {
            if (o[0].equals(OPTION_NO_PACKAGE_DIAGRAM)) {
                return false;
            }
        }
        return true;
    }

    public static LanguageVersion languageVersion() {
        return Standard.languageVersion();
    }

    public static int optionLength(String option) {
        if (OPTION_CATEGORY.equals(option)) {
            return 2;
        }

        if (OPTION_SOURCE_CLASS_PATH.equals(option)) {
            return 2;
        }

        if (OPTION_NO_PACKAGE_DIAGRAM.equals(option)) {
            return 1;
        }

        int answer = Standard.optionLength(option);

        if (option.equals(OPTION_HELP)) {
            // Print the options provided by APIviz.
            System.out.println();
            System.out.println("Provided by APIviz doclet:");
            System.out.println(OPTION_SOURCE_CLASS_PATH   + " <pathlist>     Specify where to find source class files");
            System.out.println(OPTION_NO_PACKAGE_DIAGRAM  + "               Do not generate the package diagram in the overview summary");
            System.out.println(OPTION_CATEGORY + "                       <category>[:<fillcolor>[:<linecolor>]] ");
            System.out.println("                                    Color for items marked with " + TAG_CATEGORY);
        }

        return answer;
    }
    
    public static class CustomWriterFactory extends WriterFactoryImpl
    {
		public CustomWriterFactory( ConfigurationImpl configuration )
		{
			super( configuration );
		}
		
		@Override
		public MemberSummaryWriter getMemberSummaryWriter( ClassWriter classWriter, int memberType ) throws Exception
		{
			String type = null;
			
			try
			{
				switch (memberType) {
		            case VisibleMemberMap.CONSTRUCTORS:
		                type = "constructors";
		                break;
		            case VisibleMemberMap.ENUM_CONSTANTS:
		            	type = "enum_constants";
		            	break;
		            case VisibleMemberMap.FIELDS:
		            	type = "fields";
		            	break;
		            case VisibleMemberMap.PROPERTIES:
		            	type = "properties";
		            	break;
		            case VisibleMemberMap.INNERCLASSES:
		            	type = "innerclasses";
		            	
		            	return new CustomNestedClassWriterImpl((SubWriterHolderWriter) classWriter, classWriter.getClassDoc());
		            	
		            case VisibleMemberMap.METHODS:
		            	type = "methods";
		            	break;
		            default:
		        }
			}
			finally
			{
				configuration().root.printNotice( "MemberSummaryWriter for: " + type );
			}
			
			return super.getMemberSummaryWriter( classWriter, memberType );
		}
		
		protected ConfigurationImpl configuration()
		{
			try
			{
				Field configurationField = WriterFactoryImpl.class.getDeclaredField( "configuration" );
				configurationField.setAccessible( true );
				
				return (ConfigurationImpl) configurationField.get( this );
			}
			catch (Exception e)
			{
				e.printStackTrace();
				
				return null;
			}
		}
    }
    
    public static class CustomNestedClassWriterImpl extends NestedClassWriterImpl
    {

		public CustomNestedClassWriterImpl( SubWriterHolderWriter writer, ClassDoc classdoc )
		{
			super( writer, classdoc );
		}
    	
	    /**
	     * {@inheritDoc}
	     */
	    public void addInheritedSummaryLabel(ClassDoc cd, Content inheritedTree) {
	        Content classLink = new RawHtml(writer.getPreQualifiedClassLink(LinkInfoImpl.CONTEXT_MEMBER, cd, false));
	        
	        Content notice = new StringContent( "THIS IS CONTENT WE WILL REMOVE LATER" );
	        inheritedTree.addContent(notice);
	        
	        Content label = new StringContent(
	        	cd.isInterface() ?
	            configuration().getText("doclet.Nested_Classes_Interface_Inherited_From_Interface") :
	            configuration().getText("doclet.Nested_Classes_Interfaces_Inherited_From_Class"));
	        
	        Content labelHeading = HtmlTree.HEADING(HtmlConstants.INHERITED_SUMMARY_HEADING, label);
	        
	        labelHeading.addContent(writer.getSpace());
	        labelHeading.addContent(classLink);
	        inheritedTree.addContent(labelHeading);
	    }
    }
    
    @SuppressWarnings( "restriction" )
	public static boolean start(RootDoc root) throws InstantiationException, IllegalAccessException, IllegalArgumentException, NoSuchFieldException, SecurityException, InvocationTargetException, NoSuchMethodException, ClassNotFoundException {
    	
    	final RootDoc originalRoot = root;

		ConfigurationImpl baseConfig = ConfigurationImpl.getInstance();
		
		final ConfigurationImpl customConfig = Mockito.spy( baseConfig ); //proxyClass.newInstance(); //superConstructor.newInstance();
		
		Field configurationField = customConfig.standardmessage.getClass().getDeclaredField( "configuration" );
		configurationField.setAccessible( true );
		
		Field modifiersField = Field.class.getDeclaredField( "modifiers" );
		modifiersField.setAccessible( true );
		
		modifiersField.set( configurationField, configurationField.getModifiers() & ~Modifier.FINAL );
		
		configurationField.set( customConfig.standardmessage, customConfig );
		configurationField.set( customConfig.message, customConfig );
		
		Mockito.doAnswer( new Answer<Object>() {

			@Override
			public Object answer( InvocationOnMock invocation ) throws Throwable
			{
				originalRoot.printNotice( "Calling: " + invocation + " with: " + Arrays.asList( invocation.getArguments() ) );
				
				return new CustomWriterFactory( customConfig );
			}
			
		} ).when( customConfig ).getWriterFactory();
		
    	Field instanceField = ConfigurationImpl.class.getDeclaredField( "instance" );
    	instanceField.setAccessible( true );
    	
		instanceField.set( null, customConfig );
    	
        root = new APIvizRootDoc(root);
        if (!Standard.start(root)) {
            return false;
        }

        if (!Graphviz.isAvailable(root)) {
            root.printWarning("Graphviz is not found.");
            root.printWarning("Please install graphviz and specify -Dgraphviz.home Otherwise, you might have specified incorrect graphviz home Graphviz is not found in the system path.");
            root.printWarning("Skipping diagram generation.");
            return true;
        }

        try {
            File outputDirectory = getOutputDirectory(root.options());
            ClassDocGraph graph = new ClassDocGraph(root);
            if (shouldGeneratePackageDiagram(root.options())) {
                generateOverviewSummary(root, graph, outputDirectory);
            }
            generatePackageSummaries(root, graph, outputDirectory);
            generateClassDiagrams(root, graph, outputDirectory);
        } catch(Throwable t) {
            root.printError(
                    "An error occurred during diagram generation: " +
                    t.toString());
            t.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean validOptions(String[][] options, DocErrorReporter errorReporter) {
        for (String[] o: options) {
        	System.out.println(String.format( "- Options: %s", Arrays.asList( o ) ));
        	
            if (OPTION_SOURCE_CLASS_PATH.equals(o[0])) {
                File[] cp = getClassPath(options);
                if (cp.length == 0) {
                    errorReporter.printError(
                            OPTION_SOURCE_CLASS_PATH +
                            " requires at least one valid class path.");
                    return false;
                }
                for (File f: cp) {
                    if (!f.exists() || !f.canRead()) {
                        errorReporter.printError(
                                f.toString() +
                                " doesn't exist or is not readable.");
                        return false;
                    }
                }
            }
        }

        List<String[]> newOptions = new ArrayList<String[]>();
        for (String[] o: options) {
            if (OPTION_CATEGORY.equals(o[0])) {
                continue;
            }
            if (OPTION_SOURCE_CLASS_PATH.equals(o[0])) {
                continue;
            }
            if (OPTION_NO_PACKAGE_DIAGRAM.equals(o[0])) {
                continue;
            }
            
            newOptions.add(o);
        }

        return Standard.validOptions(
                newOptions.toArray(new String[newOptions.size()][]),
                errorReporter);
    }
}
