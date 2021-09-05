// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.python.codeInsight.typing

import com.intellij.openapi.project.Project
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.QualifiedName
import com.jetbrains.python.documentation.docstrings.DocStringFormat
import com.jetbrains.python.documentation.docstrings.DocStringUtil
import com.jetbrains.python.documentation.docstrings.SectionBasedDocString
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.stubs.PyClassNameIndex
import com.jetbrains.python.psi.stubs.PyFunctionNameIndex
import com.jetbrains.python.psi.types.PyCallableParameter
import com.jetbrains.python.psi.types.PyCallableParameterImpl
import com.jetbrains.python.psi.types.PyType
import com.jetbrains.python.psi.types.TypeEvalContext

/**
 * Utilities for finding references to classes in python docstrings,
 * in which additional parameters for methods can be found.
 *
 * @author Alexander Ushaev
 */

/**
 * Returns the type of the parameter [parameterName].
 *
 * Some scientific libraries have getters according to class instance attributes, so this method just
 * simply gets the type of such getter and maps it to the parameter.
 */
fun getParamTypeFromGetter(parameterName: String, clazz: PyClass, context: TypeEvalContext): PyType? =
  clazz.methods.find { method -> method.name == "get_$parameterName" }?.let { context.getReturnType(it) }

/**
 * Returns [clazz] instance attributes with types, if possible.
 */
fun getTypedClassInstanceAttributes(clazz: PyClass, context: TypeEvalContext, vararg permittedItems: String): List<PyCallableParameter> {
  val params = mutableListOf<PyCallableParameter>()
  clazz.findInitOrNew(false, null)?.parameterList?.parameters?.forEach { param ->
    if (param.name !in permittedItems) {
      val type = param.name?.let { getParamTypeFromGetter(it, clazz, context) }
      val callableParam = PyCallableParameterImpl.nonPsi(param.name, type)
      params.add(callableParam)
    }
  }
  return params
}

/**
 * Returns class name referenced in [function] docstring from [sectionName] and [fieldName].
 *
 * This method converts plain docstring to [SectionBasedDocString] and tries to find class name in the specified
 * [sectionName] and [fieldName].
 *
 * The docstring might look like this:
 * ```
 * Other Parameters
 * ----------------
 * **kwargs : `.Line2D` properties, optional
 *    *kwargs* are used to specify properties like a line label (for
 *    auto legends), linewidth, antialiasing, marker face color.
 * ```
 * [sectionName] for such a docstring is **Other Parameters** and [fieldName] is ****kwargs**
 */
fun getReferencedClassNameFromDocstring(function: PyFunction, sectionName: String, fieldName: String): String? {
  val copyDocStringDecorator = getCopyDocstringDecorator(function)
  val docString = copyDocStringDecorator
                    ?.let { getDocstringFromCopyDocstringDecorator(it, function.project) }
                  ?: function.docStringValue
  val parsedDocString = docString?.let { DocStringUtil.parseDocStringContent(DocStringFormat.NUMPY, it.toString()) as SectionBasedDocString }
  parsedDocString?.sections?.forEach { section ->
    if (section.normalizedTitle == sectionName) {
      section.fields.forEach { field ->
        if (field.name == fieldName) {
          return (field.type ?: field.description)?.substringAfter('`')?.substringBefore('`')?.substringAfterLast('.')
        }
      }
    }
  }
  return null
}

fun getPyClassWithNamePrefix(name: String, prefix: String, callable: PyCallable): PyClass? {
  if (name.startsWith(prefix)) {
    return PyPsiFacade.getInstance(callable.project).createClassByQName(name, callable)
  }
  else {
    var result: PyClass? = null
    StubIndex.getInstance().processElements(PyClassNameIndex.KEY, name, callable.project, null, null, PyClass::class.java) { t ->
      if (t.qualifiedName?.startsWith(prefix) == true) {
        result = t
        false
      } else {
        true
      }
    }
    return result
  }
}

fun getPyFunctionWithPrefix(name: String, prefix: String, project: Project): PyFunction? {
  val results = mutableListOf<PyFunction>()
  StubIndex.getInstance().processElements(PyFunctionNameIndex.KEY, name, project, null, null, PyFunction::class.java) { t ->
    if (t.qualifiedName?.contains(prefix) == true) {
      results += t
    }
    true
  }
  return results.firstOrNull()
}

/**
 * Returns python *_copy_docstring_and_deprecators* decorator.
 */
fun getCopyDocstringDecorator(function: PyFunction): PyDecorator? =
  function.decoratorList?.decorators?.find { it.name == "_copy_docstring_and_deprecators" }

/**
 * Returns function qualified name passed to python *_copy_docstring_and_deprecators* [decorator].
 *
 * It may look like this:
 * ```
 * @_copy_docstring_and_deprecators(Axes.plot)
 * def plot(*args, scalex=True, scaley=True, data=None, **kwargs):
 * ```
 * In this case, the method will return "Axes.plot"
 */
fun getFunctionQualifiedNameFromCopyDocstringDecorator(decorator: PyDecorator): QualifiedName? =
  (decorator.arguments.firstOrNull() as PyReferenceExpression).asQualifiedName()

/**
 * Returns docstring for method passed to *_copy_docstring_and_deprecators* [decorator].
 */
fun getDocstringFromCopyDocstringDecorator(decorator: PyDecorator, project: Project): PyStringLiteralExpression? {
  val qualifiedName = getFunctionQualifiedNameFromCopyDocstringDecorator(decorator).toString()
  val methodName = qualifiedName.substringAfterLast('.')
  val function = getPyFunctionWithPrefix(methodName, qualifiedName, project)
  return function?.docStringExpression
}