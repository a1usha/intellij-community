// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.stubgen

import com.intellij.ide.CliResult
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarterBase
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.NioFiles
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiParserFacade
import com.intellij.psi.impl.file.PsiDirectoryFactoryImpl
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndexImpl
import com.jetbrains.extensions.python.toPsi
import com.jetbrains.python.codeInsight.imports.PyImportOptimizer
import com.jetbrains.python.codeInsight.typing.*
import com.jetbrains.python.documentation.PythonDocumentationProvider
import com.jetbrains.python.documentation.docstrings.SectionBasedDocString
import com.jetbrains.python.inspections.PyInspectionExtension
import com.jetbrains.python.packaging.PyPackageManager
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.types.*
import com.jetbrains.python.sdk.PythonSdkType
import com.jetbrains.python.sdk.pythonSdk
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

private const val commandName = "MatplotlibStubgen"

class MatplotlibStubGenerator : ApplicationStarterBase(commandName, 0, 1) {
  private val blackList = listOf(
    //"cbook",
    //"mpl-data",
    "sphinxext",
    //"backends",
    "testing",
    "tests",
  )

  private val methodNamesBlackList = listOf(
    "_copy_docstring_and_deprecators",
  )

  private val typingImports = listOf(
    "Annotated",
    "Any",
    "Callable",
    "ClassVar",
    "Final",
    "ForwardRef",
    "Generic",
    "Literal",
    "Optional",
    "Protocol",
    "tuple",
    "Type",
    "TypeVar",
    "Union",
    "AbstractSet",
    "ByteString",
    "Container",
    "ContextManager",
    "Hashable",
    "ItemsView",
    "Iterable",
    "Iterator",
    "KeysView",
    "Mapping",
    "MappingView",
    "MutableMapping",
    "MutableSequence",
    "MutableSet",
    "Sequence",
    "Sized",
    "ValuesView",
    "Awaitable",
    "AsyncIterator",
    "AsyncIterable",
    "Coroutine",
    "Collection",
    "AsyncGenerator",
    "AsyncContextManager",
    "Reversible",
    "SupportsAbs",
    "SupportsBytes",
    "SupportsComplex",
    "SupportsFloat",
    "SupportsIndex",
    "SupportsInt",
    "SupportsRound",
    "ChainMap",
    "Counter",
    "Deque",
    "Dict",
    "DefaultDict",
    "List",
    "OrderedDict",
    "Set",
    "FrozenSet",
    "NamedTuple",
    "TypedDict",
    "Generator",
    "AnyStr"
  )

  private val decoratorImports = listOf(
    "_api"
  )

  private val permittedDecorators = listOf(
    "writer"
  )

  private val permittedAttrs = listOf(
    "_log",
    "__author__",
    "__credits__",
    "__license__",
    "__doc__"
  )

  override fun isHeadless(): Boolean = true
  override fun getUsageMessage() = "TODO"

  override fun processCommand(args: MutableList<String>, currentDirectory: String?): Future<CliResult> {
    val future = CompletableFuture<CliResult>()

    try {
      // Required for PyImportOptimizer. Another way to make it work is to add the destination directory to the source roots.
      // See other implementations of PyInspectionExtension.overrideUnresolvedReferenceInspection.
      ApplicationManagerEx.getApplicationEx().extensionArea
        .getExtensionPoint<PyInspectionExtension>(PyInspectionExtension.EP_NAME.name)
        .registerExtension(
          object : PyInspectionExtension() {
            override fun overrideUnresolvedReferenceInspection(file: PsiFile): Boolean = true
          },
          ApplicationManager.getApplication())

      val destinationPath = args.getOrNull(1)?.let(FileUtil::expandUserHome) ?: "/Users/alex/Desktop/stubgen"
      val pathToPythonSdk = args.getOrNull(2)?.let(FileUtil::expandUserHome) ?: "/Users/alex/Desktop/stubgen-env/venv/bin/python"
      val stubsToModifyPath = args.getOrNull(3)?.let(FileUtil::expandUserHome) ?: "/Users/alex/Desktop/python-type-stubs/matplotlib"
      val mplInstallationPath =
        args.getOrNull(4)?.let(FileUtil::expandUserHome)
        ?: Paths.get(pathToPythonSdk)
          .parent
          .parent
          .resolve("lib")
          .resolve(if (com.intellij.openapi.util.SystemInfo.isWindows) "." else "python3.9")
          .resolve("site-packages/matplotlib")
          .toRealPath()
          .toString()

      println("Setting up project with python sdk...")
      val project = setupProject(pathToPythonSdk)
      println(project.pythonSdk)
      println(project.projectFilePath)

      println("Waiting indexing finishes...")

      DumbService.getInstance(project).runWhenSmart {
        try {
          runWriteAction {
            FileBasedIndexImpl.disableUpToDateCheckIn<Boolean, Exception> {
              processPackage(mplInstallationPath, destinationPath, project)
              return@disableUpToDateCheckIn true
            }
          }
        }
        catch (err: Throwable) {
          future.completeExceptionally(err)
        }
        future.complete(CliResult.OK)
      }
    }
    catch (err: Throwable) {
      future.completeExceptionally(err)
    }
    return future
  }

  private fun processPackage(packagePath: String, destinationPath: String, project: Project) {
    val rootDir = createSubGenRootPsiDirectory(destinationPath, project)
    val gen = PyElementGenerator.getInstance(project)
    val psiFacade = PsiParserFacade.SERVICE.getInstance(project)

    if (rootDir != null) {
      val processedElements = mutableListOf<String>()
      packagePath
        .takeIf { it != "none" }
        ?.let(Paths::get)
        ?.let(Files::walk)
        ?.filter(Files::isRegularFile)
        ?.forEach forEachFile@ { file ->
          val pyFile = VirtualFileManager.getInstance().findFileByNioPath(file)?.toPsi(project) as? PyFile
          if (pyFile == null || !checkBlacklist(pyFile.virtualFile.path)) return@forEachFile
          val pyiFile = createStubFileIfAbsent(rootDir, pyFile, project)

          pyFile.children.forEach forEachChild@ { psiElement ->

            if (psiElement is PyAssignmentStatement) {
              addAttributes(
                pyFile.topLevelAttributes.filter { attr -> attr.name == psiElement.leftHandSideExpression?.name },
                pyiFile,
                TypeEvalContext.codeCompletion(project, pyFile),
                gen,
                false
              )
            }

            if (psiElement !is PyQualifiedNameOwner) return@forEachChild
            if (!checkBlacklist(psiElement.qualifiedName)) return@forEachChild

            when (psiElement) {
              is PyClass -> generateStubForClass(pyiFile, psiElement, null, gen, psiFacade)
              is PyFunction -> generateStubForMethod(pyiFile, psiElement, null, null, gen, psiFacade)
            }
          }

          WriteCommandAction.runWriteCommandAction(pyiFile.project) {
            // Add Any everywhere
            pyiFile.addBefore(gen.createFromImportStatement(LanguageLevel.getDefault(), "typing", "Any", null),
              pyiFile.firstChild)
            // Add ClassVar everywhere
            pyiFile.addBefore(gen.createFromImportStatement(LanguageLevel.getDefault(), "typing", "ClassVar", null),
              pyiFile.firstChild)
          }

          // Optimize auto-imported imports
          optimizeStubFileImports(pyiFile)
          // Add from imports from .py file with aliases (required for proper import reuse)
          addImportsFromSourceFile(pyFile, pyiFile, gen)
        }
    }
  }

  private fun checkBlacklist(stringToCheck: String?): Boolean {
    if (stringToCheck == null) return false
    if (blackList.any { it in stringToCheck }) return false
    if (methodNamesBlackList.contains(stringToCheck)) return false

    return true
  }

  private fun setupProject(pathToPythonSdk: String): Project {
    val projectBase = Files.createTempDirectory("stub-")
    val projectName = "matplotlib-stubgen"
    val project =
      ProjectManagerEx.getInstanceEx().openProject(projectBase, OpenProjectTask(projectName = projectName))
      ?: error("Failed to create the project")

    val sdk = SdkConfigurationUtil.createSdk(mutableListOf<Sdk>(), pathToPythonSdk, PythonSdkType.getInstance(), null, null)
    PythonSdkType.getInstance().setupSdkPaths(sdk)

    val projectJdkTable = ProjectJdkTable.getInstance()
    if (!projectJdkTable.allJdks.contains(sdk)) {
      invokeAndWaitIfNeeded {
        runWriteAction {
          projectJdkTable.addJdk(sdk)
        }
      }
    }

    project.pythonSdk = sdk
    runWriteAction {
      val module = ModuleManager.getInstance(project).newModule(
        projectBase.resolve("module.iml").toString(),
        ModuleTypeManager.getInstance().defaultModuleType.id)
      module.rootManager.modifiableModel.run {
        val uri = "file://" + FileUtil.toSystemIndependentName(projectBase.toAbsolutePath().toString())
        addContentEntry(uri).addSourceFolder(uri, false)
        commit()
      }
      module.pythonSdk = sdk
    }
    return project
  }

  private fun createSubGenRootPsiDirectory(path: String, project: Project): PsiDirectory? {
    NioFiles.deleteRecursively(Paths.get(path))
    val directory = Files.createDirectory(Paths.get(path))

    val virtualDirectory = VirtualFileManager.getInstance().findFileByNioPath(directory)
    VfsUtil.markDirtyAndRefresh(false, true, true, virtualDirectory)

    if (virtualDirectory != null) {
      return PsiDirectoryFactoryImpl.getInstance(project).createDirectory(virtualDirectory)
    }
    else {
      return null
    }
  }

  private fun createStubFileIfAbsent(stubGenRootDirectory: PsiDirectory, sourceFile: PsiFile, project: Project): PyFile {
    var pyiFile: PyFile?

    val sitePackagesPath = PyPackageManager.getInstance(project.pythonSdk!!).refreshAndGetPackages(
      true).find { it.name == "matplotlib" }?.location
    val sdkPackagePath = sourceFile.virtualFile.toNioPath()
    val relPackagePath = Paths.get(sitePackagesPath!!).relativize(sdkPackagePath)
    val stubGenString = stubGenRootDirectory.virtualFile.toNioPath().resolve(relPackagePath).toString().replaceAfterLast('.', "pyi")
    val stubGenPath = Paths.get(stubGenString)
    pyiFile = VirtualFileManager.getInstance().findFileByNioPath(stubGenPath)?.toPsi(project) as? PyFile

    if (pyiFile == null) {
      Files.createDirectories(stubGenPath.parent)
      val stubGenFile = Files.createFile(stubGenPath)

      VfsUtil.markDirtyAndRefresh(false, true, true, stubGenRootDirectory.virtualFile)
      pyiFile = VirtualFileManager.getInstance().findFileByNioPath(stubGenFile)?.toPsi(project) as PyFile
    }
    return pyiFile
  }

  private fun generateStubForMethod(pyiFile: PyFile,
                                    sdkMethod: PyFunction,
                                    existingMethod: PyFunction?,
                                    statementsToAdd: PyStatementList?,
                                    generator: PyElementGenerator,
                                    facade: PsiParserFacade,
                                    writeToFile: Boolean = true) {
    val context = TypeEvalContext.codeCompletion(sdkMethod.project, sdkMethod.containingFile)
    if (!sdkMethod.containingFile.name.endsWith(".pyi")) {

      val returnType = PythonDocumentationProvider.getTypeHint(context.getReturnType(existingMethod ?: sdkMethod), context)
        .replace("tuple", "Tuple")

      WriteCommandAction.runWriteCommandAction(pyiFile.project) {
        addImports(context.getReturnType(sdkMethod), generator, pyiFile, context)
      }

      val psiMethod: PyFunction = generator.createFromText(LanguageLevel.getDefault(),
        PyFunction::class.java,
        "def ${sdkMethod.name}() -> $returnType: ...")

      addDecorators(sdkMethod, psiMethod, pyiFile, generator)

      val psiParams = PsiTreeUtil.findChildOfType(psiMethod, PyParameterList::class.java)!!
      val sdkHiddenParams = getHiddenMethodParams(sdkMethod, context)
      val existingHiddenParams = if (existingMethod != null) getHiddenMethodParams(existingMethod, context) else null

      addParamsToParamListExceptKwargsArgs(psiParams, sdkMethod.getParameters(context), existingMethod?.getParameters(context), pyiFile,
        generator, facade, context)
      addParamsToParamListExceptKwargsArgs(psiParams, sdkHiddenParams, existingHiddenParams, pyiFile, generator, facade, context, true,
        true)

      if (sdkMethod.getParameters(context).any { it.isPositionalContainer }) {
        addArgsParam(psiParams, generator, facade, sdkMethod.getParameters(context).any { it.isSelf })
      }
      if (sdkMethod.getParameters(context).any { it.isKeywordContainer }) addKwargsParam(psiParams, generator, facade)

      if (statementsToAdd != null) {
        statementsToAdd.addBefore(facade.createWhiteSpaceFromText("\n    "), statementsToAdd.lastChild)
        statementsToAdd.addBefore(psiMethod, statementsToAdd.lastChild)
      }

      if (writeToFile) {
        WriteCommandAction.runWriteCommandAction(sdkMethod.project) {
          pyiFile.add(psiMethod)
        }
      }
    }
  }

  private fun generateStubForClass(pyiFile: PyFile,
                                   sdkClazz: PyClass,
                                   existingClazz: PyClass?,
                                   generator: PyElementGenerator,
                                   facade: PsiParserFacade) {
    val context = TypeEvalContext.codeCompletion(sdkClazz.project, sdkClazz.containingFile)
    if (!sdkClazz.containingFile.name.endsWith(".pyi")) {

      val superClasses = sdkClazz.getSuperClasses(context).joinToString { it.name!! }
      val psiClass: PyClass = generator.createFromText(LanguageLevel.getDefault(), PyClass::class.java,
        "class ${sdkClazz.name}($superClasses):\n    pass")
      val statements = psiClass.lastChild as PyStatementList

      addAttributes(sdkClazz.classAttributes, psiClass.statementList, context, generator, true, addImports = true)
      addAttributes(sdkClazz.instanceAttributes, psiClass.statementList, context, generator, false)

      WriteCommandAction.runWriteCommandAction(pyiFile.project) {
        sdkClazz.getSuperClasses(context).forEach { superClass ->
          if (superClass.name != null && superClass.qualifiedName != null)
            pyiFile.addBefore(
              generator.createFromImportStatement(LanguageLevel.getDefault(),
                superClass.qualifiedName.toString().substringBeforeLast('.'),
                superClass.name!!,
                null), pyiFile.firstChild)
        }
      }

      addDecorators(sdkClazz, psiClass, pyiFile, generator)

      sdkClazz.methods.forEach { method ->
        val existingMethod = existingClazz?.methods?.find { it.name == method.name }
        generateStubForMethod(pyiFile, method, existingMethod, statements, generator, facade, false)
      }

      if (sdkClazz.methods.isNotEmpty()) statements.lastChild.delete()

      WriteCommandAction.runWriteCommandAction(sdkClazz.project) {
        pyiFile.add(psiClass)
      }
    }
  }

  private fun addAttributes(attributes: List<PyTargetExpression>,
                            elemToAddTo: PsiElement,
                            context: TypeEvalContext,
                            generator: PyElementGenerator,
                            isClassAttr: Boolean,
                            addImports: Boolean = false){

    WriteCommandAction.runWriteCommandAction(elemToAddTo.project) {
      attributes.filter{ !permittedAttrs.contains(it.name) }.forEach { attr ->
        val attrType = context.getType(attr)
        val attrTypeHint = PythonDocumentationProvider.getTypeHint(context.getType(attr), context)
        if (addImports) addImports(attrType, generator, elemToAddTo.containingFile, context)

        elemToAddTo.addBefore(
          generator.createFromText(
            LanguageLevel.getDefault(),
            PyTypeDeclarationStatement::class.java,
            "${attr.name}: ${if (isClassAttr) "ClassVar[${attrTypeHint}]" else attrTypeHint}"
          ), elemToAddTo.lastChild
        )
      }
    }
  }

  private fun addDecorators(toTake: PyDecoratable,
                            toAdd: PyStatement,
                            psiFile: PsiFile,
                            generator: PyElementGenerator) {
    var decorators = toTake.decoratorList?.decorators?.filter {
      decoratorImports.contains(it.qualifiedName?.firstComponent) && !permittedDecorators.contains(it.qualifiedName?.firstComponent)
    }
    if (decorators?.isEmpty() == true) decorators = null

    if (decorators != null) {
      toAdd.addBefore(generator.createDecoratorList(*decorators.map { it.text }.toTypedArray()), toAdd.firstChild)
      decorators.forEach { decorator ->
        WriteCommandAction.runWriteCommandAction(psiFile.project) {
          psiFile.addBefore(
            generator.createFromImportStatement(LanguageLevel.getDefault(),
              "matplotlib",
              decorator.qualifiedName.toString().substringBefore('.'),
              null), psiFile.firstChild)
        }
      }
    }
  }

  private fun addDocstringIfCopyDocstringDecorator(method: PyFunction,
                                                   newMethod: PyFunction,
                                                   generator: PyElementGenerator,
                                                   facade: PsiParserFacade) {
    val copyFrom = getCopyDocstringDecorator(method)
    val docstring = copyFrom?.let { getDocstringFromCopyDocstringDecorator(it, method.project) }
    val psiDocstring = docstring?.let { generator.createDocstring(docstring.text) }
    psiDocstring?.let { newMethod.addBefore(psiDocstring, newMethod.lastChild) }
  }

  private fun addParamsToParamListExceptKwargsArgs(paramsList: PyParameterList,
                                                   sdkParams: List<PyCallableParameter>,
                                                   existingParams: List<PyCallableParameter>?,
                                                   pyiFile: PsiFile,
                                                   generator: PyElementGenerator,
                                                   facade: PsiParserFacade,
                                                   context: TypeEvalContext,
                                                   markAsOptional: Boolean = false,
                                                   addCommaBefore: Boolean = false) {

    val filteredParams = sdkParams.filter { parameter ->
      ((parameter.name != null && !parameter.isPositionalContainer && !parameter.isKeywordContainer) || parameter.parameter is PySingleStarParameter)
      && !paramsList.parameters.map { it.name }.contains(parameter.name)
    }

    if (filteredParams.isNotEmpty() && addCommaBefore) {
      paramsList.addBefore(generator.createComma().psi, paramsList.lastChild)
      paramsList.addBefore(facade.createWhiteSpaceFromText("\n"), paramsList.lastChild)
    }

    filteredParams.forEachIndexed { index, attr ->
      var paramToUse = attr
      existingParams?.forEach { param ->
        if (param.name == attr.name) {
          paramToUse = param
          return@forEach
        }
      }

      WriteCommandAction.runWriteCommandAction(pyiFile.project) {
        addImports(paramToUse.getType(context), generator, pyiFile, context)
        addImports(paramToUse.defaultValue?.let { context.getType(it) }, generator, pyiFile, context)
      }

      val typeDescription = PythonDocumentationProvider.getTypeHint(paramToUse.getType(context), context)

      paramsList.addBefore(if (paramToUse.parameter is PySingleStarParameter) {
        generator.createFromText(LanguageLevel.getDefault(), PsiElement::class.java, "*")
      } else {
        generator.createParameter(
          paramToUse.getPresentableText(false),
          if (markAsOptional) "..." else paramToUse.defaultValueText,
          typeDescription,
          LanguageLevel.getDefault())
      }, paramsList.lastChild)

      if (index < filteredParams.size - 1) {
        paramsList.addBefore(generator.createComma().psi, paramsList.lastChild)
        paramsList.addBefore(facade.createWhiteSpaceFromText("\n"), paramsList.lastChild)
      }
    }
  }

  private fun addArgsParam(paramsList: PyParameterList,
                           generator: PyElementGenerator,
                           facade: PsiParserFacade,
                           asLastParam: Boolean = false) {

    if (asLastParam) {
      if (paramsList.parameters.isNotEmpty()) {
        paramsList.addBefore(generator.createComma().psi, paramsList.lastChild)
        paramsList.addBefore(facade.createWhiteSpaceFromText("\n"), paramsList.lastChild)
      }
      paramsList.addBefore(generator.createParameter("*args"), paramsList.lastChild)
    } else {
      if (paramsList.parameters.isNotEmpty()) {
        paramsList.addAfter(facade.createWhiteSpaceFromText("\n"), paramsList.firstChild)
        paramsList.addAfter(generator.createComma().psi, paramsList.firstChild)
      }
      paramsList.addAfter(generator.createParameter("*args"), paramsList.firstChild)
    }
  }

  private fun addKwargsParam(paramsList: PyParameterList,
                             generator: PyElementGenerator,
                             facade: PsiParserFacade) {
    if (paramsList.parameters.isNotEmpty()) {
      paramsList.addBefore(generator.createComma().psi, paramsList.lastChild)
      paramsList.addBefore(facade.createWhiteSpaceFromText("\n"), paramsList.lastChild)
    }
    paramsList.addBefore(generator.createParameter("**kwargs"), paramsList.lastChild)
  }

  private fun addImports(type: PyType?, generator: PyElementGenerator, psiFile: PsiFile, context: TypeEvalContext) {
    if (type == null) return

    addImport(type, generator, psiFile, context)
    val typeMembers = mutableListOf<PyType>()
    if (type is PyUnionType) typeMembers.addAll(type.members)
    typeMembers.forEach { typeMember ->
      addImports(typeMember, generator, psiFile, context)
      addImport(typeMember, generator, psiFile, context)
    }
  }

  private fun addImport(type: PyType, generator: PyElementGenerator, psiFile: PsiFile, context: TypeEvalContext) {
    val descr = PythonDocumentationProvider.getTypeHint(type, context)
    if (typingImports.contains(descr.substringBefore('['))) {
      val name = descr.substringBefore('[')
      psiFile.addBefore(
        generator.createFromImportStatement(
          LanguageLevel.getDefault(),"typing", if (name == "tuple") "Tuple" else name,null),
        psiFile.firstChild
      )
    }
    if (type is PyClassType && !type.isBuiltin) {
      if (type.classQName != null && type.name != null) {
        psiFile.addBefore(
          generator.createFromImportStatement(LanguageLevel.getDefault(),
            type.classQName!!.substringBeforeLast('.'),
            type.name!!,
            null), psiFile.firstChild)
      }
    }
  }

  private fun getHiddenMethodParams(method: PyFunction, context: TypeEvalContext): List<PyCallableParameter> {
    val paramsFromDocstring = mutableListOf<PyCallableParameter>()
    val nameFromDocString = getReferencedClassNameFromDocstring(method, SectionBasedDocString.OTHER_PARAMETERS_SECTION, "kwargs")
    if (nameFromDocString != null) {
      val clazz = getPyClassWithNamePrefix(nameFromDocString, "matplotlib", method)
      clazz?.let { getTypedClassInstanceAttributes(it, context, "self", "kwargs", "args") }?.let { paramsFromDocstring.addAll(it) }
    }

    return paramsFromDocstring.distinctBy { it.name }
  }

  private fun optimizeStubFileImports(pyiFile: PyFile) {
    WriteCommandAction.runWriteCommandAction(pyiFile.project) {
      val optimizer = PyImportOptimizer()
      optimizer.processFile(pyiFile).run()
    }
  }

  private fun addImportsFromSourceFile(pyFile: PyFile, pyiFile: PyFile, generator: PyElementGenerator) {
    WriteCommandAction.runWriteCommandAction(pyiFile.project) {
      pyFile.fromImports.forEach { importStatement ->
        if (importStatement is PyFromImportStatement) {
          if (importStatement.isStarImport) {
            pyiFile.addBefore(
              generator.createFromText(
                LanguageLevel.getDefault(),
                PyFromImportStatement::class.java,
                importStatement.text
              ), pyiFile.lastChild
            )
          } else {
            importStatement.importElements.forEach { importElement ->
              pyiFile.addBefore(generator.createFromImportStatement(
                LanguageLevel.getDefault(),
                if (importStatement.importSourceQName == null) "matplotlib" else importStatement.importSourceQName.toString(),
                importElement.text,
                importElement.text
              ), pyiFile.firstChild)
            }
          }
        }
        else {
          pyiFile.addBefore(
            generator.createFromText(LanguageLevel.getDefault(), importStatement.javaClass, importStatement.text),
            pyiFile.firstChild
          )
        }
      }
    }
  }
}