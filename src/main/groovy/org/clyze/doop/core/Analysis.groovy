package org.clyze.doop.core

import groovy.transform.TypeChecked
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.clyze.doop.blox.BloxbatchConnector
import org.clyze.doop.blox.BloxbatchScript
import org.clyze.doop.blox.WorkspaceConnector
import org.clyze.doop.input.InputResolutionContext
import org.clyze.doop.system.Executor

import java.util.regex.Pattern

import static java.io.File.separator
import static org.clyze.doop.system.CppPreprocessor.preprocess
import static org.clyze.doop.system.CppPreprocessor.preprocessAtStart

/**
 * A DOOP analysis that holds all the relevant options (vars, paths, etc) and implements all the relevant steps.
 *
 * For supporting invocations over the web, the statistic step is broken into
 * two parts: (a) produce statistics and (b) print statistics.
 *
 * The run() method is the only public method exposed by this class: no other methods should be called directly
 * by other classes.
 */
@TypeChecked
class Analysis implements Runnable {

    protected Log logger = LogFactory.getLog(getClass())

    /**
     * The unique identifier of the analysis (that determines the caching)
     */
    String id

    /**
     * The name of the analysis (that determines the logic)
     */
    String name

    /**
     * The output dir for the analysis
     */
    String outDir

    /**
     * The cache dir for the input facts
     */
    String cacheDir

    /**
     * The options of the analysis
     */
    Map<String, AnalysisOption> options

    /**
     * The analysis input resolution mechanism
     */
    InputResolutionContext ctx

    /**
     * The input jar files/dependencies of the analysis
     */
    List<File> inputs

    /**
     * The jre library jars for soot
     */
    List<String> platformLibs

    /**
     * The environment for running external commands
     */
    Map<String, String> commandsEnvironment

    Executor executor

    File facts, cacheFacts, database, exportDir, averroesDir

    BloxbatchScript lbScript

    long sootTime

    WorkspaceConnector connector

    private static final List<String> IGNORED_WARNINGS = [
            """\
        *******************************************************************
        Warning: BloxBatch is deprecated and will not be supported in LogicBlox 4.0.
        Please use 'lb' instead of 'bloxbatch'.
        *******************************************************************
        """
    ].collect{ line -> Pattern.quote(line.stripIndent()) }

    /*
     * Use a java-way to construct the instance (instead of using Groovy's automatically generated Map constructor)
     * in order to ensure that internal state is initialized at one point and the init method is no longer required.
     * This new constructor embodies the old init method and offers a ready-to-use analysis object.
     */
    protected Analysis(String id,
                       String outDir,
                       String cacheDir,
                       String name,
                       Map<String, AnalysisOption> options,
                       InputResolutionContext ctx,
                       List<File> inputs,
                       List<String> platformLibs,
                       Map<String, String> commandsEnvironment) {
        this.id = id
        this.outDir = outDir
        this.cacheDir = cacheDir
        this.name = name.replace(separator, "-")
        this.options = options
        this.ctx = ctx
        this.inputs = inputs
        this.platformLibs = platformLibs
        this.commandsEnvironment = commandsEnvironment

        executor = new Executor(commandsEnvironment)

        new File(outDir, "meta").withWriter { BufferedWriter w -> w.write(this.toString()) }

        facts       = new File(outDir, "facts")
        cacheFacts  = new File(cacheDir)
        database    = new File(outDir, "database")
        exportDir   = new File(outDir, "export")
        averroesDir = new File(outDir, "averroes")

        // Create workspace connector (needed by the post processor and the server-side analysis execution)
        connector = new BloxbatchConnector(database, commandsEnvironment)
    }

    @Override
    void run() {
        /*
         Initialize the writer here and not in the constructor, in order to allow an analysis to be re-run.
         */
        lbScript    = new BloxbatchScript(new File(outDir, "run.lb"))

        generateFacts()
        if (options.X_STOP_AT_FACTS.value) return

        initDatabase()
        if (!options.X_STOP_AT_INIT.value) {

            basicAnalysis()
            if (!options.X_STOP_AT_BASIC.value) {

                mainAnalysis()

                try {
                    File f = Helper.checkFileOrThrowException("${Doop.analysesPath}/${name}/refinement-delta.logic", "No refinement-delta.logic for ${name}")
                    logger.info "-- Re-Analyze --"
                    reanalyze()
                }
                catch(e) {
                    logger.debug e.getMessage()
                }

                produceStats()

            }
        }

        lbScript.close()

        logger.info "Using generated script ${lbScript.getPath()}"
        logger.info "\nAnalysis START"
        long t = timing {
            def bloxOpts = options.BLOX_OPTS.value ?: ''
            executor.execute(outDir, "${options.BLOXBATCH.value} -script ${lbScript.getPath()} $bloxOpts", IGNORED_WARNINGS)
        }
        logger.info "Analysis END\n"
        int dbSize = (FileUtils.sizeOfDirectory(database) / 1024).intValue()
        bloxbatchPipe database, """-addBlock 'Stats:Runtime("script wall-clock time (sec)", $t).
                                              Stats:Runtime("disk footprint (KB)", $dbSize).'"""
    }


    /**
     * @return A string representation of the analysis
     */
    String toString() {
        return [id:id, name:name, outDir:outDir, cacheDir:cacheDir, inputs:ctx.toString()].collect { Map.Entry entry -> "${entry.key}=${entry.value}" }.join("\n") +
                "\n" +
                options.values().collect { AnalysisOption option -> option.toString() }.sort().join("\n") + "\n"
    }

    protected void generateFacts() {

        FileUtils.deleteQuietly(facts)
        facts.mkdirs()

        if (cacheFacts.exists() && options.CACHE.value) {
            logger.info "Using cached facts from $cacheFacts"
            Helper.copyDirectoryContents(cacheFacts, facts)
        }
        else {
            logger.info "-- Fact Generation --"

            if (options.RUN_JPHANTOM.value) {
                runJPhantom()
            }

            if (options.RUN_AVERROES.value) {
                runAverroes()
            }

            runSoot()

            FileUtils.touch(new File(facts, "ApplicationClass.facts"))
            FileUtils.touch(new File(facts, "Properties.facts"))

            if (options.TAMIFLEX.value) {
                File origTamFile  = new File(options.TAMIFLEX.value.toString())

                new File(facts, "Tamiflex.facts").withWriter { w ->
                    origTamFile.eachLine { line ->
                        w << line
                                .replaceFirst(/;[^;]*;$/, "")
                                .replaceFirst(/;$/, ";0")
                                .replaceFirst(/(^.*;.*)\.([^.]+;[0-9]+$)/) { full, first, second -> first+";"+second+"\n" }
                    }
                }
            }

            logger.info "Caching facts in $cacheFacts"
            FileUtils.deleteQuietly(cacheFacts)
            cacheFacts.mkdirs()
            Helper.copyDirectoryContents(facts, cacheFacts)
            new File(cacheFacts, "meta").withWriter { BufferedWriter w -> w.write(cacheMeta()) }
        }
    }

    private String cacheMeta() {
        Collection<String> inputJars = inputs.collect {
            File file -> file.toString()
        }
        Collection<String> cacheOptions = options.values().findAll {
            it.forCacheID
        }.collect {
            AnalysisOption option -> option.toString()
        }.sort()
        return (inputJars + cacheOptions).join("\n")
    }

    protected void initDatabase() {

        FileUtils.deleteQuietly(database)
        FileUtils.copyFile(new File("${Doop.factsPath}/declarations.logic"),
                           new File("${outDir}/facts-declarations.logic"))
        FileUtils.copyFile(new File("${Doop.factsPath}/flow-insensitivity-declarations.logic"),
                           new File("${outDir}/flow-insensitivity-declarations.logic"))
        FileUtils.copyFile(new File("${Doop.factsPath}/entities-import.logic"),
                           new File("${outDir}/entities-import.logic"))
        FileUtils.copyFile(new File("${Doop.factsPath}/import.logic"),
                           new File("${outDir}/facts-import.logic"))
        FileUtils.copyFile(new File("${Doop.factsPath}/flow-insensitivity-delta.logic"),
                           new File("${outDir}/flow-insensitivity-delta.logic"))

        lbScript
            .createDB(database.getName())
            .echo("-- Init DB --")
            .startTimer()
            .transaction()
            .addBlockFile("facts-declarations.logic")
            .addBlockFile("flow-insensitivity-declarations.logic")
            .addBlock("""Stats:Runtime("soot-fact-generation time (sec)", $sootTime).""")
            .executeFile("entities-import.logic")
            .executeFile("facts-import.logic")
            .executeFile("flow-insensitivity-delta.logic")

        if (options.TAMIFLEX.value) {
            def tamiflexDir = "${Doop.addonsPath}/tamiflex"

            FileUtils.copyFile(new File("${tamiflexDir}/fact-declarations.logic"),
                    new File("${outDir}/tamiflex-fact-declarations.logic"))
            FileUtils.copyFile(new File("${tamiflexDir}/import.logic"),
                    new File("${outDir}/tamiflex-import.logic"))
            FileUtils.copyFile(new File("${tamiflexDir}/post-import.logic"),
                    new File("${outDir}/tamiflex-post-import.logic"))
            lbScript
                    .addBlockFile("tamiflex-fact-declarations.logic")
                    .executeFile("tamiflex-import.logic")
                    .addBlockFile("tamiflex-post-import.logic")
        }

        if (options.MAIN_CLASS.value)
            lbScript.execute("""+MainClass(x) <- ClassType(x), Type:fqn(x:"${options.MAIN_CLASS.value}").""")

        lbScript
                .commit()
                .elapsedTime()

        if (options.TRANSFORM_INPUT.value)
            runTransformInput()
    }


    protected void basicAnalysis() {
        if (options.DYNAMIC.value) {
            List<String> dynFiles = options.DYNAMIC.value as List<String>
            dynFiles.eachWithIndex { String dynFile, Integer index ->
                File f = new File(dynFile)
                File dynImport = new File(outDir, "dynamic${index}.import")
                Helper.writeToFile dynImport, """\
                                              option,delimiter,"\t"
                                              option,hasColumnNames,false

                                              fromFile,"${f.getCanonicalPath()}",a,inv,b,type
                                              toPredicate,Config:DynamicClass,type,inv
                                              """.toString().stripIndent()

                lbScript.wr("import -f $dynImport")
            }
        }

        def factMacros = "${Doop.factsPath}/macros.logic"
        preprocess(this, "${Doop.logicPath}/basic/basic.logic", "${outDir}/basic.logic", factMacros)

        lbScript
            .echo("-- Basic Analysis --")
            .startTimer()
            .transaction()
            .addBlockFile("basic.logic")

        if (options.CFG_ANALYSIS.value) {
            FileUtils.copyFile(new File("${Doop.addonsPath}/cfg-analysis/declarations.logic"),
                               new File("${outDir}/cfg-analysis-declarations.logic"))
            lbScript.addBlockFile("cfg-analysis-declarations.logic")

            preprocess(this, "${Doop.addonsPath}/cfg-analysis/rules.logic", "${outDir}/cfg-analysis-rules.logic")
            lbScript.addBlockFile("cfg-analysis-rules.logic")
        }

        lbScript
            .commit()
            .elapsedTime()
    }

    /**
     * Performs the main part of the analysis.
     */
    protected void mainAnalysis() {
        def factMacros   = "${Doop.factsPath}/macros.logic"
        def macros       = "${Doop.analysesPath}/${name}/macros.logic"
        def corePath     = "${Doop.analysesPath}/core"
        def analysisPath = "${Doop.analysesPath}/${name}"

        // By default, assume we run a context-sensitive analysis
        boolean isContextSensitive = true
        try {
            File f = Helper.checkFileOrThrowException("${analysisPath}/analysis.properties", "No analysis.properties for ${name}")
            Properties props = Helper.loadProperties(f)
            isContextSensitive = props.getProperty("is_context_sensitive").toBoolean()
        }
        catch(e) {
            logger.debug e.getMessage()
        }
        if (isContextSensitive) {
            preprocess(this, "${analysisPath}/declarations.logic", "${outDir}/${name}-declarations.logic", "${corePath}/context-sensitivity-declarations.logic")
            preprocess(this, "${analysisPath}/delta.logic", "${outDir}/${name}-delta.logic", factMacros, "${corePath}/core-delta.logic")
            preprocess(this, "${analysisPath}/analysis.logic", "${outDir}/${name}.logic", factMacros, macros, "${corePath}/context-sensitivity.logic")
        }
        else {
            preprocess(this, "${analysisPath}/declarations.logic", "${outDir}/${name}-declarations.logic")
            preprocess(this, "${analysisPath}/delta.logic", "${outDir}/${name}-delta.logic")
            preprocess(this, "${analysisPath}/analysis.logic", "${outDir}/${name}.logic")
        }

        lbScript
            .echo("-- Prologue --")
            .startTimer()
            .transaction()
            .addBlockFile("${name}-declarations.logic")
            .executeFile("${name}-delta.logic")

        if (options.SANITY.value) {
            lbScript.addBlockFile("${Doop.addonsPath}/sanity.logic")
        }

        if (options.ENABLE_REFLECTION.value) {
            String reflectionPath = "${Doop.analysesPath}/core/reflection"

            preprocess(this, "${reflectionPath}/delta.logic", "${outDir}/reflection-delta.logic")
            FileUtils.copyFile(new File("${reflectionPath}/allocations-delta.logic"),
                               new File("${outDir}/reflection-allocations-delta.logic"))
            lbScript
                .checkpoint()
                .executeFile("reflection-delta.logic")
                .checkpoint()
                .executeFile("reflection-allocations-delta.logic")
                .checkpoint()
        }

        /**
         * Generic file for incrementally adding addons logic from various
         * points. This is necessary in some cases to avoid weird errors from
         * the engine (DELTA_RECURSION etc.) and in general it helps
         * performance-wise.
         */
        File addons = new File(outDir, "addons.logic")
        FileUtils.deleteQuietly(addons)
        FileUtils.touch(addons)

        if (options.INFORMATION_FLOW.value) {
            FileUtils.copyFile(new File("${Doop.addonsPath}/information-flow/declarations.logic"),
                               new File("${outDir}/information-flow-declarations.logic"))
            preprocess(this, "${Doop.addonsPath}/information-flow/delta.logic", "${outDir}/information-flow-delta.logic", macros)
            lbScript
                .addBlockFile("information-flow-declarations.logic")
            logger.info "Adding Information flow rules to addons logic"
            preprocessAtStart(this, "${Doop.addonsPath}/information-flow/rules.logic", "${outDir}/addons.logic")

            lbScript.commit().transaction().executeFile("information-flow-delta.logic")
        }

        if (options.DACAPO.value || options.DACAPO_BACH.value) {
            FileUtils.copyFile(new File("${Doop.addonsPath}/dacapo/declarations.logic"),
                               new File("${outDir}/dacapo-declarations.logic"))
            preprocess(this, "${Doop.addonsPath}/dacapo/delta.logic", "${outDir}/dacapo-delta.logic", macros)
            lbScript
                    .addBlockFile("dacapo-declarations.logic")
                    .executeFile("dacapo-delta.logic")

            logger.info "Adding DaCapo rules to addons logic"
            preprocessAtStart(this, "${Doop.addonsPath}/dacapo/rules.logic", "${outDir}/addons.logic")
        }



        if (options.TAMIFLEX.value) {
            FileUtils.copyFile(new File("${Doop.addonsPath}/tamiflex/declarations.logic"),
                               new File("${outDir}/tamiflex-declarations.logic"))
            FileUtils.copyFile(new File("${Doop.addonsPath}/tamiflex/delta.logic"),
                               new File("${outDir}/tamiflex-delta.logic"))
            lbScript
                    .addBlockFile("tamiflex-declarations.logic")
                    .executeFile("tamiflex-delta.logic")

            logger.info "Adding tamiflex rules to addons logic"

            preprocessAtStart(this, "${Doop.addonsPath}/tamiflex/rules.logic", "${outDir}/addons.logic")
        }

        if (options.REFINE.value)
            refine()

        preprocessAtStart(this, "${outDir}/addons.logic", "${outDir}/${name}.logic", macros)

        lbScript
                .commit()
                .elapsedTime()
                .echo("-- Main Analysis --")
                .startTimer()
                .transaction()
                .addBlockFile("${name}.logic")
                .commit()
                .elapsedTime()

        if (options.MUST.value) {
            FileUtils.copyFile(new File("${Doop.analysesPath}/must-point-to/may-pre-analysis.logic"),
                               new File("${outDir}/must-point-to-may-pre-analysis.logic"))
            preprocess(this, "${Doop.analysesPath}/must-point-to/analysis-simple.logic", "${outDir}/must-point-to.logic")


            lbScript
                    .echo("-- Pre Analysis (for Must) --")
                    .startTimer()
                    .transaction()
                    .addBlockFile("must-point-to-may-pre-analysis.logic")
                    .addBlock("RootMethodForMustAnalysis(?meth) <- MethodSignature:DeclaringType[?meth] = ?class, ApplicationClass(?class), Reachable(?meth).")
                    .commit()
                    .elapsedTime()
                    .echo("-- Must Analysis --")
                    .startTimer()
                    .transaction()
                    .addBlockFile("must-point-to.logic")
                    .commit()
                    .elapsedTime()
        }
    }

    /**
     * Reanalyze.
     */
    protected void reanalyze() {
        logger.info "Loading ${name} refinement-delta rules"

        preprocess(this, "${Doop.analysesPath}/${name}/refinement-delta.logic", "${outDir}/${name}-refinement-delta.logic")
        // TODO: handle exportCsv in script
        timing {
            bloxbatchPipe database, "-execute -file ${outDir}/${name}-refinement-delta.logic"
        }

        timing {
            bloxbatchPipe database, "-exportCsv TempSiteToRefine -overwrite -exportDataDir $outDir -exportFilePrefix ${name}-"
        }

        timing {
            bloxbatchPipe database, "-exportCsv TempNegativeSiteFilter -overwrite -exportDataDir $outDir -exportFilePrefix ${name}-"
        }

        timing {
            bloxbatchPipe database, "-exportCsv TempObjectToRefine -overwrite -exportDataDir $outDir -exportFilePrefix ${name}-"
        }

        timing {
            bloxbatchPipe database, "-exportCsv TempNegativeObjectFilter -overwrite -exportDataDir $outDir -exportFilePrefix ${name}-"
        }

        generateFacts()
        initDatabase()
        //TODO: We don't need to write-meta, do we?
        options.REFINE.value = true
        mainAnalysis()
    }

    protected void refine() {

        //The files and their contents
        Map<String, String> files = [
                "refine-site": """\
                               option,delimiter,","
                               option,hasColumnNames,false
                               option,quotedValues,true
                               option,escapeQuotedValues,true

                               fromFile,"${outDir}/${name}-TempSiteToRefine.csv",CallGraphEdgeSource,CallGraphEdgeSource
                               toPredicate,SiteToRefine,CallGraphEdgeSource""".toString().stripIndent(),

                "negative-site": """\
                                 option,delimiter,","
                                 option,hasColumnNames,false

                                 fromFile,"${outDir}/${name}-TempNegativeSiteFilter.csv",string,string
                                 toPredicate,NegativeSiteFilter,string""".toString().stripIndent(),

                "refine-object": """\
                                 option,delimiter,","
                                 option,hasColumnNames,false
                                 option,quotedValues,true
                                 option,escapeQuotedValues,true

                                 fromFile,"${outDir}/${name}-TempObjectToRefine.csv",HeapAllocation,HeapAllocation
                                 toPredicate,ObjectToRefine,HeapAllocation""".toString().stripIndent(),

                "negative-object": """\
                                   option,delimiter,","
                                   option,hasColumnNames,false

                                   fromFile,"${outDir}/${name}-TempNegativeObjectFilter.csv",string,string
                                   toPredicate,NegativeObjectFilter,string""".toString().stripIndent()
        ]

        logger.info "loading $name refinement facts "
        files.each { Map.Entry<String, String> entry ->
            File f = new File(outDir, "${name}-${entry.key}.import")
            Helper.writeToFile f, entry.value
            Helper.checkFileOrThrowException(f, "Could not create import file: $f")
            lbScript.wr("import -f $f")
        }
    }

    protected void runTransformInput() {
        preprocess(this, "${Doop.addonsPath}/transform/rules.logic", "${outDir}/transform.logic", "${Doop.addonsPath}/transform/declarations.logic")
        lbScript
                .echo("-- Transforming Facts --")
                .startTimer()
                .transaction()
                .addBlockFile("${outDir}/transform.logic")
                .commit()

        2.times { int i ->
            lbScript
                .echo(""" "-- Transformation (step $i) --" """)
                .transaction()
                .executeFile("${Doop.addonsPath}/transform/delta.logic")
                .commit()
        }
        lbScript.elapsedTime()
    }

    protected void produceStats() {
        if (options.X_STATS_NONE.value) return;

        if (options.X_STATS_AROUND.value) {
            lbScript.include(options.X_STATS_AROUND.value as String)
            return
        }

        def statsPath = "${Doop.addonsPath}/statistics"
        preprocess(this, "${statsPath}/statistics-simple.logic", "${outDir}/statistics-simple.logic")

        lbScript
                .echo("-- Statistics --")
                .startTimer()
                .transaction()
                .addBlockFile("statistics-simple.logic")

        if (options.X_STATS_FULL.value) {
            preprocess(this, "${statsPath}/statistics.logic", "${outDir}/statistics.logic")
            lbScript.addBlockFile("statistics.logic")
        }

        lbScript
            .commit()
            .elapsedTime()
    }

    protected void runJPhantom(){
        logger.info "-- Running jphantom to generate complement jar --"

        String jar = inputs[0].toString()
        String jarName = FilenameUtils.getBaseName(jar)
        String jarExt = FilenameUtils.getExtension(jar)
        String newJar = "${jarName}-complemented.${jarExt}"
        String[] params = [jar, "-o", "${outDir}/$newJar", "-d", "${outDir}/phantoms", "-v", "0"]
        logger.debug "Params of jphantom: ${params.join(' ')}"

        //we invoke the main method reflectively to avoid adding jphantom as a compile-time dependency
        ClassLoader loader = phantomClassLoader()
        Helper.execJava(loader, "org.clyze.jphantom.Driver", params)

        //set the jar of the analysis to the complemented one
        File f = Helper.checkFileOrThrowException("$outDir/$newJar", "jphantom invocation failed")
        inputs[0] = f
    }

    protected void runAverroes() {
        logger.info "-- Running averroes --"

        ClassLoader loader = averroesClassLoader()
        Helper.execJava(loader, "org.eclipse.jdt.internal.jarinjarloader.JarRsrcLoader", null)
    }

    protected void runSoot() {
        Collection<String> depArgs

        def platform = options.PLATFORM.value.toString().tokenize("_")[0]
        assert platform == "android" || platform == "java"

        if (options.RUN_AVERROES.value) {
            //change linked arg and injar accordingly
            inputs[0] = Helper.checkFileOrThrowException("$averroesDir/organizedApplication.jar", "Averroes invocation failed")
            depArgs = ["-l", "$averroesDir/placeholderLibrary.jar".toString()]
        }
        else {
            Collection<String> deps = inputs.drop(1).collect{ File f -> ["-l", f.toString()]}.flatten() as Collection<String>
            depArgs = platformLibs.collect{ String arg -> ["-l", arg]}.flatten() +  deps
        }

        Collection<String> params = null;

        switch(platform) {
            case "java":
                params = ["--full", "--keep-line-number"] + depArgs + ["--application-regex", options.APP_REGEX.value.toString()]
                break
            case "android":
                params = ["--full", "--keep-line-number"] + depArgs + ["--android-jars", options.PLATFORM_LIBS.value.toString() + separator + "Android" + separator + "Sdk" + separator + "platforms"]
                break
            default:
                throw new RuntimeException("Unsupported platform")
        }

        if (options.SSA.value) {
            params = params + ["--ssa"]
        }

        if (!options.RUN_JPHANTOM.value) {
            params = params + ["--allow-phantom"]
        }

        if (options.USE_ORIGINAL_NAMES.value) {
            params = params + ["--use-original-names"]
        }

        if (options.ONLY_APPLICATION_CLASSES_FACT_GEN.value) {
            params = params + ["--only-application-classes-fact-gen"]
        }

        params = params + ["-d", facts.toString(), inputs[0].toString()]
        logger.debug "Params of soot: ${params.join(' ')}"

        sootTime = timing {
            //We invoke soot reflectively using a separate class-loader to be able 
            //to support multiple soot invocations in the same JVM @ server-side.
            //TODO: Investigate whether this approach may lead to memory leaks,
            //not only for soot but for all other Java-based tools, like jphantom
            //or averroes. 
            //In such a case, we should invoke all Java-based tools using a 
            //separate process.
            ClassLoader loader = sootClassLoader()
            Helper.execJava(loader, "org.clyze.doop.soot.Main", params.toArray(new String[params.size()]))
        }
    }

    /**
     * Creates a new class loader for running jphantom
     */
    private ClassLoader phantomClassLoader() {
        return copyOfCurrentClasspath()
    }

    /**
     * Creates a new class loader for running soot
     */
    private ClassLoader sootClassLoader() {
        return copyOfCurrentClasspath()
    }

    private ClassLoader copyOfCurrentClasspath() {
        URLClassLoader loader = this.getClass().getClassLoader() as URLClassLoader
        URL[] classpath = loader.getURLs()
        return new URLClassLoader(classpath, null as ClassLoader)
    }

    /**
     * Creates a new class loader for running averroes
     */
    private ClassLoader averroesClassLoader() {
        //TODO: for now, we hard-code the averroes jar and properties
        String jar = "${Doop.doopHome}/lib/averroes-no-properties.jar"
        String properties = "$outDir/averroes.properties"

        //Determine the library jars
        Collection<String> libraryJars = inputs.drop(1).collect { it.toString() } + jreAverroesLibraries()

        //Create the averroes properties
        Properties props = new Properties()
        props.setProperty("application_includes", options.APP_REGEX.value as String)
        props.setProperty("main_class", options.MAIN_CLASS as String)
        props.setProperty("input_jar_files", inputs[0].toString())
        props.setProperty("library_jar_files", libraryJars.join(":"))

        //Concatenate the dynamic files
        if (options.DYNAMIC.value) {
            List<String> dynFiles = options.DYNAMIC.value as List<String>
            File dynFileAll = new File(outDir, "all.dyn")
            dynFiles.each {String dynFile ->
                dynFileAll.append new File(dynFile).text
            }
            props.setProperty("dynamic_classes_file", dynFileAll.toString())
        }

        props.setProperty("tamiflex_facts_file", options.TAMIFLEX.value as String)
        props.setProperty("output_dir", averroesDir as String)
        props.setProperty("jre", javaAverroesLibrary())

        new File(properties).newWriter().withWriter { Writer writer ->
            props.store(writer, null)
        }

        File f1 = Helper.checkFileOrThrowException(jar, "averroes jar missing or invalid: $jar")
        File f2 = Helper.checkFileOrThrowException(properties, "averroes properties missing or invalid: $properties")

        List<URL> classpath = [f1.toURI().toURL(), f2.toURI().toURL()]
        return new URLClassLoader(classpath as URL[])
    }

    /**
     * Generates a list for the jre libs for averroes 
     */
    private List<String> jreAverroesLibraries() {

        def platformLibsValue = options.PLATFORM.value.toString().tokenize("_")
        assert platformLibsValue.size() == 2
        def (platform, version) = [platformLibsValue[0], platformLibsValue[1]]
        assert platform == "java"

        String path = "${options.DOOP_PLATFORM_LIBS.value}/JREs/jre1.${version}/lib"

        //Not using if/else for readability
        switch(version) {
            case "1.3":
                return []
            case "1.4":
                return ["${path}/jce.jar", "${path}/jsse.jar"] as List<String>
            case "1.5":
                return ["${path}/jce.jar", "${path}/jsse.jar"] as List<String>
            case "1.6":
                return ["${path}/jce.jar", "${path}/jsse.jar"] as List<String>
            case "1.7":
                return ["${path}/jce.jar", "${path}/jsse.jar"] as List<String>
            case "system":
                String javaHome = System.getProperty("java.home")
                return ["$javaHome/lib/jce.jar", "$javaHome/lib/jsse.jar"] as List<String>
        }
    }

    /**
     * Generates the full path to the rt.jar required by averroes
     */
    private String javaAverroesLibrary() {

        def platformLibsValue = options.PLATFORM.value.toString().tokenize("_")
        assert platformLibsValue.size() == 2
        def (platform, version) = [platformLibsValue[0], platformLibsValue[1]]
        assert platform == "java"

        String path = "${options.DOOP_PLATFORM_LIBS.value}/JREs/jre1.${version}/lib"
        return "$path/rt.jar"
    }

    /**
     * Invokes bloxbatch on the given database with the given params, piping it up with supplied pipeCommands.
     */
    private void bloxbatchPipe(File database, String params, String... pipeCommands) {
        String command = "${options.BLOXBATCH.value} -db $database $params"
        if (pipeCommands)
            command += " | ${pipeCommands.join(" |")}"

        executor.execute(command, IGNORED_WARNINGS)
    }

    private long timing(Closure c) {
        long now = System.currentTimeMillis()
        try {
            c.call()
        }
        catch(e) {
            throw e
        }
        //we measure the time only in error-free cases
        return ((System.currentTimeMillis() - now) / 1000).longValue()
    }
}
