package com.omegaup.runner

import com.omegaup.libinteractive.target.Generator
import com.omegaup.libinteractive.target.InstallVisitor
import com.omegaup.libinteractive.target.OutputFile
import com.omegaup.libinteractive.target.OutputLink
import com.omegaup.libinteractive.target.OutputPath
import com.omegaup.libinteractive.target.Options
import com.omegaup.libinteractive.idl.Parser

import java.io._
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import scala.collection.{mutable,immutable}
import com.omegaup._
import com.omegaup.data._

class Runner(name: String, sandbox: Sandbox) extends RunnerService with Log with Using {
  def name() = name

  def isInterpreted(lang: String) = lang == "py" || lang == "rb"

  private def compileStatus(
      lang: String, runDirectory: File, target: String, sourceFiles: Iterable[String],
      previousError: String, errorString: String,
      status: Int): CompileOutputMessage = {
    var compileError = "Compiler failed to run"

    if(status >= 0) {
      val targetFile = new File(runDirectory, sandbox.targetFileName(lang, target))
      val missingTarget = !targetFile.exists
    
      if (previousError == null && status == 0 && !missingTarget) {
        if (!Config.get("runner.preserve", false)) {
          new File(runDirectory, "compile.meta").delete
          new File(runDirectory, "compile.out").delete
          new File(runDirectory, "compile.err").delete

          // Clean up any extra files generated by the compilation.
          if (!isInterpreted(lang)) {
            for (source <- sourceFiles) {
              val sourceFile = new File(source)
              if (sourceFile.exists) {
                sourceFile.delete
              }
            }
          }
        }
    
        info("compile finished successfully")
        return new CompileOutputMessage(
          token = Some(runDirectory.getParentFile.getName))
      }

      val meta = MetaFile.load(runDirectory.getCanonicalPath + "/compile.meta")
  
      compileError =
        if (previousError != null)
          previousError
        else if (meta("status") == "TO")
          "Compilation time exceeded"
        else if (meta.contains("message") && meta("status") != "RE")
          meta("message")
        else if (lang == "pas")
          FileUtil
            .read(new File(runDirectory, "compile.out"))
            .replace(runDirectory.getCanonicalPath + "/", "")
        else
          FileUtil
            .read(new File(runDirectory, "compile.err"))
            .replace(runDirectory.getCanonicalPath + "/", "")

      if (compileError == "" && missingTarget) {
        compileError = s"""Class should be called "$target"."""
      }
    }

    if (!Config.get("runner.preserve", false)) {
      FileUtil.deleteDirectory(runDirectory.getParentFile.getCanonicalPath)
    }
  
    error("compile finished with errors: {}", compileError)
    new CompileOutputMessage(errorString, error = Some(compileError))
  }

  def compile(runDirectory: File,
              lang: String,
              codes: List[(String, String)],
              error_string: String): CompileOutputMessage = {
    runDirectory.mkdirs
    
    val inputFiles = new mutable.ListBuffer[String]
    
    for ((name, code) <- codes) {
      if (name.contains("/")) {
        return new CompileOutputMessage(error_string, error=Some("invalid filenames"))
      }
      inputFiles += runDirectory.getCanonicalPath + "/" + name
      using (new FileWriter(new File(runDirectory, name))) { fileWriter => {
        fileWriter.write(code, 0, code.length)
      }}
    }

    if (lang == "cat") {
      // Literal. We're done.
      info("compile finished successfully")
      return new CompileOutputMessage(token = Some(runDirectory.getParentFile.getName))
    }

    // Store the first compilation error for multi-file Pascal.
    var previousError: String = null
  
    // Workaround for fpc's weird rules regarding compilation order.
    var pascalMain = runDirectory.getCanonicalPath + "/" + "Main.pas"
    if (inputFiles.contains(pascalMain) && inputFiles.size > 1) {
      // Exclude Main.pas
      inputFiles -= pascalMain

      // Files need to be compiled individually.
      for (inputFile <- inputFiles) {
        sandbox.compile(
          "pas",
          List(inputFile),
          chdir = runDirectory.getCanonicalPath,
          metaFile = runDirectory.getCanonicalPath + "/compile.meta",
          outputFile = "compile.out",
          errorFile = "compile.err"
        ) { status => {
          if(status >= 0) {
            val meta = MetaFile.load(runDirectory.getCanonicalPath + "/compile.meta")

            if (status != 0 && previousError == null) {
              previousError = 
                if (meta("status") == "TO")
                  "Compilation time exceeded"
                else
                  FileUtil.read(runDirectory.getCanonicalPath + "/compile.out")
                    .replace(runDirectory.getCanonicalPath + "/", "")
            }
          } else {
            previousError = "Unable to compile " + inputFile
          }
        }}
      }

      // Now use the regular case to compile Main.
      inputFiles.clear
      inputFiles += pascalMain
    }
 
    sandbox.compile(
      lang,
      inputFiles,
      chdir = runDirectory.getCanonicalPath,
      metaFile = runDirectory.getCanonicalPath + "/compile.meta",
      outputFile = "compile.out",
      errorFile = "compile.err"
    ) {
      compileStatus(lang, runDirectory, "Main", inputFiles, previousError,
        error_string, _)
    }
  }

  def compile(runDirectory: File,
              parent: (String, String),
              child: (String, String),
              interactive: InteractiveDescription,
              debug: Boolean): CompileOutputMessage = {
    runDirectory.mkdirs
    val parser = new Parser

    val idl = parser.parse(interactive.idlSource)
    val options = Options(
      parentLang = interactive.parentLang,
      childLang = interactive.childLang,
      moduleName = interactive.moduleName,
      pipeDirectories = true
    )
    val installer = new InstallVisitor(runDirectory.toPath, Paths.get(""))
    Generator.generate(idl, options,
      Paths.get("$parent"), Paths.get("$child")).map(_ match {
        case link: OutputLink => {
          if (link.target.getFileName.toString == "$parent") {
            OutputFile(link.path, parent._2)
          } else {
            OutputFile(link.path, child._2)
          }
        }
        case path: OutputPath => path
      }).foreach(installer.apply)

    var targets = List(
      (interactive.parentLang, Paths.get("$parent"), true),
      (interactive.childLang, Paths.get("$child"), false)
    )

    for ((lang, path, parent) <- targets) {
      var target = Generator.target(lang, idl,
          options, path, parent)
      for (makefile <- target.generateMakefileRules) {
        val runRoot = Paths.get(runDirectory.getCanonicalPath)
        val targetRoot = runRoot.resolve(makefile.target.getParent)
        val sources = makefile.requisites.map(runRoot.resolve(_).toString)

        val result = sandbox.compile(
          lang,
          sources,
          chdir = targetRoot.toString,
          metaFile = targetRoot.resolve(Paths.get("compile.meta")).toString,
          outputFile = "compile.out",
          errorFile = "compile.err",
          target = makefile.target.getFileName.toString,
          extraFlags = (if (parent) {
            lang match {
                case "c" => List("-Wl,-e__entry")
                case "cpp" => List("-Wl,-e__entry")
                case "cpp11" => List("-Wl,-e__entry")
                case _ => List()
            }
          } else List())
        ) {
          compileStatus(lang, targetRoot.toFile, targetRoot.getFileName.toString,
            sources, null, "compile error", _)
        }

        if (result.status != "ok") {
          return result
        }
      }
    }

    new CompileOutputMessage(token = Some(runDirectory.getParentFile.getName))
  }
  
  def compile(message: CompileInputMessage): CompileOutputMessage = {
    info("compile {}", message.lang)
    
    val compileDirectory = new File(Config.get("compile.root", "."))
    compileDirectory.mkdirs
    
    var runDirectoryFile = File.createTempFile(System.nanoTime.toString, null, compileDirectory)
    runDirectoryFile.delete
    
    val runRoot =
      runDirectoryFile
        .getCanonicalPath
        .substring(0, runDirectoryFile.getCanonicalPath.length - 4) + "." + message.lang

    message.master_lang match {
      case Some(master_lang) => {
        message.master_code match {
          case Some(master_code) => {
            val master_result = compile(new File(runRoot + "/validator"),
                                        master_lang,
                                        master_code,
                                        "judge error")
            
            if (master_result.status != "ok") {
              return master_result
            }
           
            FileUtil.write(new File(runRoot, "validator/lang"), master_lang)
          }
          case None => {
            return new CompileOutputMessage("judge error", error = Some("Missing code"))
          }
        }
      }
      case None => {}
    }
   
    message.interactive match {
      case None =>
        compile(new File(runRoot, "bin"), message.lang, message.code, "compile error")
      case Some(interactive) =>
        compile(
          new File(runRoot, "bin"),
          message.code(1),
          message.code(0),
          interactive,
          message.debug
        )
    }
  }

  def run(message: RunInputMessage, callback: RunCaseCallback) : RunOutputMessage = {
    info("run {}", message)
    val casesDirectory:File = message.input match {
      case Some(in) => {
        if (in.contains(".") || in.contains("/")) {
          return new RunOutputMessage(status="error", error=Some("Invalid input"))
        }
        new File (Config.get("input.root", "."), in)
      }
      case None => null
    }
    
    if(message.token.contains("..") || message.token.contains("/")) {
      return new RunOutputMessage(status="error", error=Some("Invalid token"))
    }
    
    if(casesDirectory != null && !casesDirectory.exists) {
      new RunOutputMessage(status="error", error=Some("missing input"))
    } else {
      val runDirectory = new File(Config.get("compile.root", "."), message.token)
    
      if(!runDirectory.exists) return new RunOutputMessage(status="error", error=Some("Invalid token"))
    
      val binDirectory = new File(runDirectory.getCanonicalPath, "bin")
    
      val lang = message.token.substring(message.token.indexOf(".")+1)

      if (lang == "cat") {
        // Literal. Just copy the "program" as the output and produce a fake .meta.
        try {
          debug("Literal submission {}", new File(binDirectory, "Main.cat"))
          using (new FileInputStream(new File(binDirectory, "Main.cat"))) { fileStream => {
            using (new ZipInputStream(new DataUriInputStream(fileStream))) { stream => {
              debug("Literal stream")
              val inputFiles = casesDirectory.listFiles
                                             .filter {_.getName.endsWith(".in")}
                                             .map { _.getName }
              var entry: ZipEntry = stream.getNextEntry
      
              while(entry != null) {
                debug("Literal stream: {}", entry.getName)
                val caseName = FileUtil.removeExtension(FileUtil.basename(entry.getName))
                if (entry.getName.endsWith(".out") && inputFiles.contains(caseName + ".in")) {
                  using (new FileOutputStream(new File(runDirectory, caseName + ".out"))) {
                    FileUtil.copy(stream, _)
                  }
                  FileUtil.write(new File(runDirectory, caseName + ".meta").getCanonicalPath,
                                 "time:0\ntime-wall:0\nmem:0\nstatus:OK")
                  process(message, runDirectory, casesDirectory, lang, new File(runDirectory, caseName + ".meta"), callback)
                }
                stream.closeEntry
                entry = stream.getNextEntry
              }
            }}
          }}
        } catch {
          case e: Exception => {
            warn("Literal submission: {}", e)
            val caseName = runDirectory.getCanonicalPath + "/Main"
            FileUtil.copy(new File(binDirectory, "Main.cat"), new File(caseName + ".out"))
            FileUtil.write(caseName + ".meta",
                           "time:0\ntime-wall:0\nmem:0\nstatus:OK")
            process(message, runDirectory, casesDirectory, lang, new File(caseName + ".meta"), callback)
          }
        }
      } else {
        val casePaths = ((if(casesDirectory != null) {
          casesDirectory.listFiles.toList.filter {_.getName.endsWith(".in")} .map { x => {
            new File(casesDirectory, FileUtil.removeExtension(x.getName))
              .getCanonicalPath
          }}
        } else List()) ++ (message.cases match {
          case None => List()
          case Some(extra) => {
            extra.map { x => {
              val caseName = x.name
              val casePath = new File(runDirectory, caseName).getCanonicalPath
            
              FileUtil.write(casePath + ".in", x.data)
              casePath
            }}
          }
        })).sortWith(numericSort)

        val timeLimiter = new OverallRunTimeLimiter(
          message.overallWallTimeLimit)
        for (casePath <- casePaths) {
          val outputPath = new File(binDirectory.getParentFile, new File(casePath).getName).getCanonicalPath
          timeLimiter.run(outputPath) {
            message.interactive match {
              case None => {
                // Do a normal run.
                sandbox.run(
                  message,
                  lang,
                  chdir = binDirectory.getCanonicalPath,
                  metaFile = outputPath + ".meta",
                  inputFile = casePath + ".in",
                  outputFile = outputPath + ".out",
                  errorFile = outputPath + ".err"
                )
              }
              case Some(interactive) => {
                interactiveRun(
                  message,
                  interactive,
                  lang,
                  binDirectory,
                  casePath
                )
              }
            }
          }

          process(message, runDirectory, casesDirectory, lang,
            new File(outputPath + ".meta"), callback)
        }
      }
    
      info("run finished token={}", message.token)
      
      new RunOutputMessage()
    }
  }

  def interactiveRun(message: RunInputMessage, interactive: InteractiveRuntimeDescription, lang: String, binDirectory: File, casePath: String) = {
    val runtime = Runtime.getRuntime
    val main = interactive.main

    // Create all pipes and mount points
    for (interface <- interactive.interfaces :+ main) {
      new File(binDirectory, s"${interface}/${interface}_pipes")
          .mkdir
      if (interface != main) {
        new File(binDirectory, s"${interface}/${main}_pipes")
            .mkdir
      }
      val pipeDir = new File(binDirectory, s"${interface}_pipes")
      pipeDir.mkdir
      pusing (runtime.exec(Array[String](
          "/usr/bin/mkfifo",
          new File(pipeDir, "pipe").getCanonicalPath))
      ) {
        _.waitFor
      }
    }
    for (interface <- interactive.interfaces) {
      new File(binDirectory, s"${main}/${interface}_pipes").mkdir
    }

    // Simultaneously run all executables.
    val threads = Executors.newFixedThreadPool(
        interactive.interfaces.length + 1)
    val outputPath = new File(binDirectory.getParentFile, new File(casePath).getName).getCanonicalPath
    threads.submit(new Runnable() {
      override def run(): Unit = {
        sandbox.run(
          message,
          interactive.parentLang,
          chdir = new File(binDirectory, main)
              .getCanonicalPath,
          metaFile = s"${outputPath}.meta",
          inputFile = s"${casePath}.in",
          outputFile = s"${outputPath}.out",
          errorFile = s"${outputPath}_${main}.err",
          target = interactive.parentLang match {
            case "java" => s"${main}_entry"
            case "py" => main  // Parent Python does not need entry
            case _ => main
          },
          // Mount all the named pipe directories.
          extraMountPoints = List(
            (new File(binDirectory, s"${main}_pipes")
                .getCanonicalPath,
            s"/home/${main}_pipes")
          ) ++ interactive.interfaces.map { interface => {
            (new File(binDirectory, s"${interface}_pipes")
                .getCanonicalPath,
            s"/home/${interface}_pipes")
          }},
          // Pass in the name of the case (without extension) as the first
          // parameter in case the problemsetter program is also acting as
          // validator.
          extraParams = List(new File(casePath).getName)
        )
      }
    })
    for (interface <- interactive.interfaces) {
      threads.submit(new Runnable() {
        override def run(): Unit = {
          sandbox.run(
            message,
            lang,
            chdir = new File(binDirectory, interface).getCanonicalPath,
            metaFile = s"${outputPath}_${interface}.meta",
            inputFile = "/dev/null",
            outputFile = s"${outputPath}_${interface}.out",
            errorFile = s"${outputPath}_${interface}.err",
            target = lang match {
              case "java" => s"${interface}_entry"
              case "py" => s"${interface}_entry"
              case _ => interface
            },
            // Mount all the named pipe directories.
            extraMountPoints = List(
              (
                new File(binDirectory, s"${interface}_pipes")
                  .getCanonicalPath,
                s"/home/${interface}_pipes"
              ),
              (
                new File(binDirectory, s"${main}_pipes")
                  .getCanonicalPath,
                s"/home/${main}_pipes"
              )
            )
          )
        }
      })
    }
    threads.shutdown
    threads.awaitTermination(Long.MaxValue, TimeUnit.SECONDS)

    // Concatenate all output and error files into the one error file.
    val errorSources = List(new File(s"${outputPath}_${main}.err")) ++
      interactive.interfaces.flatMap{ interface => {
        List(
          new File(s"${outputPath}_${interface}.out"),
          new File(s"${outputPath}_${interface}.err")
        )
      }}

    using (new PrintWriter(new FileWriter(s"${outputPath}.err"))) { err => {
      for (src <- errorSources) {
        err.println(src.getName)
        err.println("=" * src.getName.length)
        err.println(FileUtil.read(src))
        err.println
        src.delete
      }
    }}

    // Generate the final .meta file.
    val parentMeta = MetaFile.load(s"${outputPath}.meta")

    val childrenMetaFiles = interactive.interfaces.map {
      interface => new File(s"${outputPath}_${interface}.meta")
    }
    val childrenMeta = childrenMetaFiles.map{
      file => MetaFile.load(file.getCanonicalPath)
    }
    childrenMetaFiles.foreach(_.delete)
    var time = 0.0
    var timeWall = 0.0
    var mem = 0L
    var chosenMeta: Option[scala.collection.Map[String, String]] = None

    for (child <- childrenMeta) {
      if (child("status") != "OK" && chosenMeta.isEmpty) {
        chosenMeta = Some(child)
      }

      if (child.contains("time")) {
        time += child("time").toDouble
      }
      if (child.contains("time-wall")) {
        timeWall = Math.max(timeWall, child("time-wall").toDouble)
      }
      if (child.contains("mem")) {
        mem = Math.max(mem, child("mem").toLong)
      }
    }

    val childMeta = (chosenMeta match {
      case None => scala.collection.Map[String, String](
        "status" -> "OK",
        "return" -> "0"
      )
      case Some(chosen) => chosen
    }) ++ List(
      "time" -> time.toString,
      "time-wall" -> timeWall.toString,
      "mem" -> mem.toString
    )

    val finalMeta = (
      if (childMeta("status") == "OK" && parentMeta("status") != "OK") {
        error("Child processes finished correctly, but parent did not {}",
          parentMeta)
        parentMeta + ("status" -> "JE") + ("error" -> "Child process finished correctly, but parent did not")
      } else {
        childMeta
      }
    )

    MetaFile.save(s"${outputPath}.meta", finalMeta)
  }

  def process(message: RunInputMessage, runDirectory: File, casesDirectory: File, lang: String,
      metaFile: File, callback: RunCaseCallback): Unit = {
    val meta = MetaFile.load(metaFile.getCanonicalPath)
    var addedErr = false
    var addedOut = false
  
    if(meta("status") == "OK") {
      val validatorDirectory = new File(runDirectory, "validator")
      if (validatorDirectory.exists) {
        val caseName = FileUtil.removeExtension(metaFile.getName)
        val caseFile = new File(validatorDirectory, caseName).getCanonicalPath;
        var inputFile = new File(FileUtil.removeExtension(metaFile.getCanonicalPath) + ".in")
        if (!inputFile.exists) {
          inputFile = new File(casesDirectory, caseName + ".in")
        }
        
        val validator_lang = FileUtil.read(new File(validatorDirectory, "lang"))

        sandbox.run(message,
                    validator_lang,
                    logTag = "Validator run",
                    extraParams = List(caseName, lang),
                    chdir = validatorDirectory.getCanonicalPath,
                    metaFile = caseFile + ".meta",
                    inputFile = FileUtil.removeExtension(metaFile.getCanonicalPath) + ".out",
                    outputFile = caseFile + ".out",
                    errorFile = caseFile + ".err",
                    originalInputFile = Some(inputFile.getCanonicalPath),
                    runMetaFile = Some(metaFile.getCanonicalPath)
        )

        if (message.debug) {
          publish(callback, new File(caseFile + ".meta"), "validator/")
          publish(callback, new File(caseFile + ".out"), "validator/")
          publish(callback, new File(caseFile + ".err"), "validator/")
        }
        
        val metaAddendum = try {
          using (new BufferedReader(new FileReader(caseFile + ".out"))) { reader => {
            List(
              ("score" -> math.max(0.0, math.min(1.0, reader.readLine.trim.toDouble)).toString)
            )
          }}
        } catch {
          case e: Exception => {
            error("validador " + caseFile + ".out", e)
            List(("status", "JE"), ("error", "file `validator/" + caseName + ".out' missing or empty"))
          }
        }
        
        MetaFile.save(metaFile.getCanonicalPath, meta ++ metaAddendum)
      }
      
      publish(callback, new File(metaFile.getCanonicalPath.replace(".meta", ".out")))
      addedOut = true
    } else if((meta("status") == "RE" && lang == "java") ||
              (meta("status") == "SG" && lang == "cpp") ||
              (meta("status") == "SG" && lang == "cpp11")) {
      publish(callback, new File(metaFile.getCanonicalPath.replace(".meta", ".err")))
      addedErr = true
    }
    
    publish(callback, metaFile)

    if (message.debug) {
      if (!addedErr) {
        publish(callback, new File(metaFile.getCanonicalPath.replace(".meta", ".err")))
      }
      if (!addedOut) {
        publish(callback, new File(metaFile.getCanonicalPath.replace(".meta", ".out")))
      }
    }
  }

  def publish(callback: RunCaseCallback, file: File, prefix: String = "") = {
    using (new FileInputStream(file)) {
      debug("Publishing {} {}", file, file.length)
      callback(prefix + file.getName, file.length, _)
    }
  }
  
  def removeCompileDir(token: String): Unit = {
    val runDirectory = new File(Config.get("compile.root", ".") + "/" + token)
   
    if (!runDirectory.exists) throw new IllegalArgumentException("Invalid token")

    if (!Config.get("runner.preserve", false)) {
      error("Removing directory {}", runDirectory)
      FileUtil.deleteDirectory(runDirectory)
    }
  }

  def input(inputName: String, entries: Iterable[InputEntry]): InputOutputMessage = {
    val inputDirectory = new File(Config.get("input.root", "."), inputName)
    inputDirectory.mkdirs

    try {
      // SHA1SUMS is a safe filename, since all input files have the .in extension.
      using (new PrintWriter(new File(inputDirectory, "SHA1SUMS"))) { sha1 => {
        for (entry <- entries) {
          using (new InflaterInputStream(entry.data)) { blob => {
            blob.skip(5)
            var size = 0L
            var cur = 0
            while ({ cur = blob.read ; cur > 0 }) {
              size = 10 * size + (cur - '0')
            }

            using (new FileOutputStream(new File(inputDirectory, entry.name))) { out => {
              val hash = FileUtil.copy_sha1(blob, out)
              sha1.printf("%s  %s\n", hash, entry.name)
            }}
          }}
        }
      }}

      new InputOutputMessage()
    } catch {
      case e: Exception => {
        FileUtil.deleteDirectory(inputDirectory)
        throw e
      }
    }
  }

  private def numericSort(a: String, b: String): Boolean = {
    var i = 0
    var j = 0

    while (i < a.length && j < b.length) {
      if (Character.isDigit(a(i)) && Character.isDigit(b(j))) {
        var x = 0L
        while (i < a.length && Character.isDigit(a(i))) {
          x = x * 10 + Character.getNumericValue(a(i))
          i += 1
        }
        var y = 0L
        while (j < b.length && Character.isDigit(b(j))) {
          y = y * 10 + Character.getNumericValue(b(j))
          j += 1
        }
        if (x != y) {
          return x < y;
        }
      } else {
        if (a(i) != b(j)) {
          return a(i) < b(j);
        }
        i += 1
        j += 1
      }
    }

    return j < b.length
  }
}

class OverallRunTimeLimiter(limit: Long) {
  var wall_msecs: Long = 0

  def run(casePath: String) (f: => Unit) = {
    if (wall_msecs < limit) {
      val t0 = System.currentTimeMillis
      try {
        f
      } finally {
        val t1 = System.currentTimeMillis
        wall_msecs += Math.max(0, t1 - t0)
      }
    }

    if (wall_msecs >= limit) {
      val meta = scala.collection.Map[String, String](
        "status" -> "TO",
        "time" -> "0.0",
        "time-wall" -> "0.0",
        "mem" -> "0",
        "overall-wall-time-exceeded" -> "true"
      )

      MetaFile.save(casePath + ".meta", meta)
    }
  }
}
