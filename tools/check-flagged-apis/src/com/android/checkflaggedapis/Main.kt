/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:JvmName("Main")

package com.android.checkflaggedapis

import android.aconfig.Aconfig
import com.android.tools.metalava.model.BaseItemVisitor
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.FieldItem
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.text.ApiFile
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node

/**
 * Class representing the fully qualified name of a class, method or field.
 *
 * This tool reads a multitude of input formats all of which represents the fully qualified path to
 * a Java symbol slightly differently. To keep things consistent, all parsed APIs are converted to
 * Symbols.
 *
 * Symbols are encoded using the format similar to the one described in section 4.3.2 of the JVM
 * spec [1], that is, "package.class.inner-class.method(int, int[], android.util.Clazz)" is
 * represented as
 * <pre>
 *   package.class.inner-class.method(II[Landroid/util/Clazz;)
 * <pre>
 *
 * Where possible, the format has been simplified (to make translation of the
 * various input formats easier): for instance, only / is used as delimiter (#
 * and $ are never used).
 *
 * 1. https://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.3.2
 */
internal sealed class Symbol {
  companion object {
    private val FORBIDDEN_CHARS = listOf('#', '$', '.')

    fun createClass(clazz: String, superclass: String?, interfaces: Set<String>): Symbol {
      return ClassSymbol(
          toInternalFormat(clazz),
          superclass?.let { toInternalFormat(it) },
          interfaces.map { toInternalFormat(it) }.toSet())
    }

    fun createField(clazz: String, field: String): Symbol {
      require(!field.contains("(") && !field.contains(")"))
      return MemberSymbol(toInternalFormat(clazz), toInternalFormat(field))
    }

    fun createMethod(clazz: String, method: String): Symbol {
      return MemberSymbol(toInternalFormat(clazz), toInternalFormat(method))
    }

    protected fun toInternalFormat(name: String): String {
      var internalName = name
      for (ch in FORBIDDEN_CHARS) {
        internalName = internalName.replace(ch, '/')
      }
      return internalName
    }
  }

  abstract fun toPrettyString(): String
}

internal data class ClassSymbol(
    val clazz: String,
    val superclass: String?,
    val interfaces: Set<String>
) : Symbol() {
  override fun toPrettyString(): String = "$clazz"
}

internal data class MemberSymbol(val clazz: String, val member: String) : Symbol() {
  override fun toPrettyString(): String = "$clazz/$member"
}

/**
 * Class representing the fully qualified name of an aconfig flag.
 *
 * This includes both the flag's package and name, separated by a dot, e.g.:
 * <pre>
 *   com.android.aconfig.test.disabled_ro
 * <pre>
 */
@JvmInline
internal value class Flag(val name: String) {
  override fun toString(): String = name.toString()
}

internal sealed class ApiError {
  abstract val symbol: Symbol
  abstract val flag: Flag
}

internal data class EnabledFlaggedApiNotPresentError(
    override val symbol: Symbol,
    override val flag: Flag
) : ApiError() {
  override fun toString(): String {
    return "error: enabled @FlaggedApi not present in built artifact: symbol=${symbol.toPrettyString()} flag=$flag"
  }
}

internal data class DisabledFlaggedApiIsPresentError(
    override val symbol: Symbol,
    override val flag: Flag
) : ApiError() {
  override fun toString(): String {
    return "error: disabled @FlaggedApi is present in built artifact: symbol=${symbol.toPrettyString()} flag=$flag"
  }
}

internal data class UnknownFlagError(override val symbol: Symbol, override val flag: Flag) :
    ApiError() {
  override fun toString(): String {
    return "error: unknown flag: symbol=${symbol.toPrettyString()} flag=$flag"
  }
}

class CheckCommand :
    CliktCommand(
        help =
            """
Check that all flagged APIs are used in the correct way.

This tool reads the API signature file and checks that all flagged APIs are used in the correct way.

The tool will exit with a non-zero exit code if any flagged APIs are found to be used in the incorrect way.
""") {
  private val apiSignaturePath by
      option("--api-signature")
          .help(
              """
              Path to API signature file.
              Usually named *current.txt.
              Tip: `m frameworks-base-api-current.txt` will generate a file that includes all platform and mainline APIs.
              """)
          .path(mustExist = true, canBeDir = false, mustBeReadable = true)
          .required()
  private val flagValuesPath by
      option("--flag-values")
          .help(
              """
            Path to aconfig parsed_flags binary proto file.
            Tip: `m all_aconfig_declarations` will generate a file that includes all information about all flags.
            """)
          .path(mustExist = true, canBeDir = false, mustBeReadable = true)
          .required()
  private val apiVersionsPath by
      option("--api-versions")
          .help(
              """
            Path to API versions XML file.
            Usually named xml-versions.xml.
            Tip: `m sdk dist` will generate a file that includes all platform and mainline APIs.
            """)
          .path(mustExist = true, canBeDir = false, mustBeReadable = true)
          .required()

  override fun run() {
    val flaggedSymbols =
        apiSignaturePath.toFile().inputStream().use {
          parseApiSignature(apiSignaturePath.toString(), it)
        }
    val flags = flagValuesPath.toFile().inputStream().use { parseFlagValues(it) }
    val exportedSymbols = apiVersionsPath.toFile().inputStream().use { parseApiVersions(it) }
    val errors = findErrors(flaggedSymbols, flags, exportedSymbols)
    for (e in errors) {
      println(e)
    }
    throw ProgramResult(errors.size)
  }
}

internal fun parseApiSignature(path: String, input: InputStream): Set<Pair<Symbol, Flag>> {
  val output = mutableSetOf<Pair<Symbol, Flag>>()
  val visitor =
      object : BaseItemVisitor() {
        override fun visitClass(cls: ClassItem) {
          getFlagOrNull(cls)?.let { flag ->
            val symbol =
                Symbol.createClass(
                    cls.baselineElementId(),
                    cls.superClass()?.baselineElementId(),
                    cls.allInterfaces()
                        .map { it.baselineElementId() }
                        .filter { it != cls.baselineElementId() }
                        .toSet())
            output.add(Pair(symbol, flag))
          }
        }

        override fun visitField(field: FieldItem) {
          getFlagOrNull(field)?.let { flag ->
            val symbol =
                Symbol.createField(field.containingClass().baselineElementId(), field.name())
            output.add(Pair(symbol, flag))
          }
        }

        override fun visitMethod(method: MethodItem) {
          getFlagOrNull(method)?.let { flag ->
            val methodName = buildString {
              append(method.name())
              append("(")
              method.parameters().joinTo(this, separator = "") { it.type().internalName() }
              append(")")
            }
            val symbol = Symbol.createMethod(method.containingClass().qualifiedName(), methodName)
            output.add(Pair(symbol, flag))
          }
        }

        private fun getFlagOrNull(item: Item): Flag? {
          return item.modifiers
              .findAnnotation("android.annotation.FlaggedApi")
              ?.findAttribute("value")
              ?.value
              ?.let { Flag(it.value() as String) }
        }
      }
  val codebase = ApiFile.parseApi(path, input)
  codebase.accept(visitor)
  return output
}

internal fun parseFlagValues(input: InputStream): Map<Flag, Boolean> {
  val parsedFlags = Aconfig.parsed_flags.parseFrom(input).getParsedFlagList()
  return parsedFlags.associateBy(
      { Flag("${it.getPackage()}.${it.getName()}") },
      { it.getState() == Aconfig.flag_state.ENABLED })
}

internal fun parseApiVersions(input: InputStream): Set<Symbol> {
  fun Node.getAttribute(name: String): String? = getAttributes()?.getNamedItem(name)?.getNodeValue()

  val output = mutableSetOf<Symbol>()
  val factory = DocumentBuilderFactory.newInstance()
  val parser = factory.newDocumentBuilder()
  val document = parser.parse(input)

  val classes = document.getElementsByTagName("class")
  // ktfmt doesn't understand the `..<` range syntax; explicitly call .rangeUntil instead
  for (i in 0.rangeUntil(classes.getLength())) {
    val cls = classes.item(i)
    val className =
        requireNotNull(cls.getAttribute("name")) {
          "Bad XML: <class> element without name attribute"
        }
    var superclass: String? = null
    val interfaces = mutableSetOf<String>()
    val children = cls.getChildNodes()
    for (j in 0.rangeUntil(children.getLength())) {
      val child = children.item(j)
      when (child.getNodeName()) {
        "extends" -> {
          superclass =
              requireNotNull(child.getAttribute("name")) {
                "Bad XML: <extends> element without name attribute"
              }
        }
        "implements" -> {
          val interfaceName =
              requireNotNull(child.getAttribute("name")) {
                "Bad XML: <implements> element without name attribute"
              }
          interfaces.add(interfaceName)
        }
      }
    }
    output.add(Symbol.createClass(className, superclass, interfaces))
  }

  val fields = document.getElementsByTagName("field")
  // ktfmt doesn't understand the `..<` range syntax; explicitly call .rangeUntil instead
  for (i in 0.rangeUntil(fields.getLength())) {
    val field = fields.item(i)
    val fieldName =
        requireNotNull(field.getAttribute("name")) {
          "Bad XML: <field> element without name attribute"
        }
    val className =
        requireNotNull(field.getParentNode()?.getAttribute("name")) {
          "Bad XML: top level <field> element"
        }
    output.add(Symbol.createField(className, fieldName))
  }

  val methods = document.getElementsByTagName("method")
  // ktfmt doesn't understand the `..<` range syntax; explicitly call .rangeUntil instead
  for (i in 0.rangeUntil(methods.getLength())) {
    val method = methods.item(i)
    val methodSignature =
        requireNotNull(method.getAttribute("name")) {
          "Bad XML: <method> element without name attribute"
        }
    val methodSignatureParts = methodSignature.split(Regex("\\(|\\)"))
    if (methodSignatureParts.size != 3) {
      throw Exception("Bad XML: method signature '$methodSignature'")
    }
    var (methodName, methodArgs, _) = methodSignatureParts
    val packageAndClassName =
        requireNotNull(method.getParentNode()?.getAttribute("name")) {
              "Bad XML: top level <method> element, or <class> element missing name attribute"
            }
            .replace("$", "/")
    if (methodName == "<init>") {
      methodName = packageAndClassName.split("/").last()
    }
    output.add(Symbol.createMethod(packageAndClassName, "$methodName($methodArgs)"))
  }

  return output
}

/**
 * Find errors in the given data.
 *
 * @param flaggedSymbolsInSource the set of symbols that are flagged in the source code
 * @param flags the set of flags and their values
 * @param symbolsInOutput the set of symbols that are present in the output
 * @return the set of errors found
 */
internal fun findErrors(
    flaggedSymbolsInSource: Set<Pair<Symbol, Flag>>,
    flags: Map<Flag, Boolean>,
    symbolsInOutput: Set<Symbol>
): Set<ApiError> {
  fun Set<Symbol>.containsSymbol(symbol: Symbol): Boolean {
    // trivial case: the symbol is explicitly listed in api-versions.xml
    if (contains(symbol)) {
      return true
    }

    // non-trivial case: the symbol could be part of the surrounding class'
    // super class or interfaces
    val (className, memberName) =
        when (symbol) {
          is ClassSymbol -> return false
          is MemberSymbol -> {
            Pair(symbol.clazz, symbol.member)
          }
        }
    val clazz = find { it is ClassSymbol && it.clazz == className } as? ClassSymbol?
    if (clazz == null) {
      return false
    }

    for (interfaceName in clazz.interfaces) {
      // createMethod is the same as createField, except it allows parenthesis
      val interfaceSymbol = Symbol.createMethod(interfaceName, memberName)
      if (contains(interfaceSymbol)) {
        return true
      }
    }

    return false
  }
  val errors = mutableSetOf<ApiError>()
  for ((symbol, flag) in flaggedSymbolsInSource) {
    try {
      if (flags.getValue(flag)) {
        if (!symbolsInOutput.containsSymbol(symbol)) {
          errors.add(EnabledFlaggedApiNotPresentError(symbol, flag))
        }
      } else {
        if (symbolsInOutput.containsSymbol(symbol)) {
          errors.add(DisabledFlaggedApiIsPresentError(symbol, flag))
        }
      }
    } catch (e: NoSuchElementException) {
      errors.add(UnknownFlagError(symbol, flag))
    }
  }
  return errors
}

fun main(args: Array<String>) = CheckCommand().main(args)