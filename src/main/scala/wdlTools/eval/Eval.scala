package wdlTools.eval

import wdlTools.eval.WdlValues._
import wdlTools.syntax.{SourceLocation, WdlVersion}
import wdlTools.types.ExprState.ExprState
import wdlTools.types.{ExprState, TypeUtils, WdlTypes, TypedAbstractSyntax => TAT}
import dx.util.{Bindings, EvalPaths, FileSource, FileSourceResolver, LocalFileSource, Logger}

import scala.collection.immutable.TreeSeqMap

object Eval {
  lazy val empty: Eval =
    Eval(DefaultEvalPaths.empty, None, Vector.empty, FileSourceResolver.get, Logger.get)

  def createBindingsFromEnv(env: Map[String, (WdlTypes.T, V)]): Bindings[String, V] = {
    WdlValueBindings(env.map { case (name, (_, v)) => name -> v })
  }
}

case class Eval(paths: EvalPaths,
                wdlVersion: Option[WdlVersion],
                userDefinedFunctions: Vector[UserDefinedFunctionImplFactory] = Vector.empty,
                fileResovler: FileSourceResolver = FileSourceResolver.get,
                logger: Logger = Logger.get,
                allowNonstandardCoercions: Boolean = false) {
  // choose the standard library implementation based on version
  private lazy val standardLibrary = {
    wdlVersion match {
      case None =>
        throw new RuntimeException(
            "cannot evaluate standard library functions without a WDL version"
        )
      case Some(ver) => Stdlib(paths, ver, userDefinedFunctions, fileResovler, logger)
    }
  }

  private case class Context(bindings: Bindings[String, V] = WdlValueBindings.empty,
                             exprState: ExprState = ExprState.Start) {

    def apply(name: String): V = {
      bindings
        .get(name)
        .getOrElse(
            throw new EvalException(s"accessing undefined variable ${name}")
        )
    }

    def advanceState(condition: Option[ExprState] = None): Context = {
      if (condition.exists(exprState >= _)) {
        throw new Exception(s"Context in an invalid state ${exprState} >= ${condition.get}")
      }
      val newState = exprState match {
        case ExprState.Start    => ExprState.InString
        case ExprState.InString => ExprState.InPlaceholder
        case _                  => throw new Exception(s"Cannot advance state from ${exprState}")
      }
      copy(exprState = newState)
    }
  }

  private object Context {
    def empty: Context = Context()
  }

  // Access a field in a struct or an object. For example:
  //   Int z = x.a
  private def exprGetName(value: V, id: String, loc: SourceLocation): V = {
    value match {
      case V_Struct(name, members) =>
        members.get(id) match {
          case None =>
            throw new EvalException(s"Struct ${name} does not have member ${id}", loc)
          case Some(t) => t
        }

      case V_Object(members) =>
        members.get(id) match {
          case None =>
            throw new EvalException(s"Object does not have member ${id}", loc)
          case Some(t) => t
        }

      case V_Call(name, members) =>
        members.get(id) match {
          case None =>
            throw new EvalException(s"Call object ${name} does not have member ${id}", loc)
          case Some(t) => t
        }

      // accessing a pair element
      case V_Pair(l, _) if id.toLowerCase() == "left"  => l
      case V_Pair(_, r) if id.toLowerCase() == "right" => r
      case V_Pair(_, _) =>
        throw new EvalException(s"accessing a pair with (${id}) is illegal", loc)

      case _ =>
        throw new EvalException(s"member access (${id}) in expression is illegal", loc)
    }
  }

  // within an interpolation, null/None renders as empty string
  private def interpolationValueToString(V: V, loc: SourceLocation): String = {
    V match {
      // within an interpolation, null/None renders as empty string
      case V_Null | V_Optional(V_Null) => ""
      case other =>
        EvalUtils.formatPrimitive(other, loc)
    }
  }

  private def validateDefault(value: V, loc: SourceLocation): Unit = {
    try {
      EvalUtils.unwrapOptional(value) match {
        case V_File(path)      => fileResovler.resolve(path)
        case V_Directory(path) => fileResovler.resolveDirectory(path)
        case _                 => ()
      }
    } catch {
      case t: Throwable => throw new EvalException(t.getMessage, loc)
    }
  }

  private def apply(expr: TAT.Expr,
                    ctx: Context,
                    validate: (V, SourceLocation) => Unit = validateDefault): V = {
    def inner(nestedExpr: TAT.Expr, nestedCtx: Context): V = {
      val v = nestedExpr match {
        case _: TAT.ValueNull      => V_Null
        case _: TAT.ValueNone      => V_Null
        case x: TAT.ValueBoolean   => V_Boolean(x.value)
        case x: TAT.ValueInt       => V_Int(x.value)
        case x: TAT.ValueFloat     => V_Float(x.value)
        case x: TAT.ValueString    => V_String(x.value)
        case x: TAT.ValueFile      => V_File(x.value)
        case x: TAT.ValueDirectory => V_Directory(x.value)

        // complex types
        case TAT.ExprPair(left, right, _, _) =>
          V_Pair(inner(left, nestedCtx), inner(right, nestedCtx))
        case TAT.ExprArray(value, _, _) =>
          V_Array(value.map { x =>
            inner(x, nestedCtx)
          })
        case TAT.ExprMap(value, _, _) =>
          V_Map(value.map {
            case (k, v) => inner(k, nestedCtx) -> inner(v, nestedCtx)
          })
        case TAT.ExprObject(value, _, _) =>
          V_Object(
              value
                .map {
                  case (k, v) =>
                    // an object literal key can be a string or identifier
                    val key = inner(k, nestedCtx) match {
                      case V_String(s) => s
                      case _ =>
                        throw new EvalException(
                            s"invalid key ${k}, object literal key must be a string",
                            expr.loc
                        )
                    }
                    key -> inner(v, nestedCtx)
                }
                .to(TreeSeqMap)
          )

        case TAT.ExprIdentifier(id, _, _) if nestedCtx.bindings.contains(id) =>
          nestedCtx.bindings(id)
        case TAT.ExprIdentifier(id, _, _) =>
          throw new EvalException(s"identifier ${id} not found")

        // interpolation
        case TAT.ExprCompoundString(value, _, _) =>
          // concatenate an array of strings inside an expression/command block
          val strArray: Vector[String] = value.map { expr =>
            val v = inner(expr, nestedCtx.advanceState(Some(ExprState.InString)))
            interpolationValueToString(v, expr.loc)
          }
          V_String(strArray.mkString(""))
        case TAT.ExprPlaceholderCondition(t, f, boolExpr, _, _) =>
          // ~{true="--yes" false="--no" boolean_value}
          inner(boolExpr, nestedCtx.advanceState(Some(ExprState.InPlaceholder))) match {
            case V_Boolean(true) =>
              inner(t, nestedCtx.advanceState(Some(ExprState.InPlaceholder)))
            case V_Boolean(false) =>
              inner(f, nestedCtx.advanceState(Some(ExprState.InPlaceholder)))
            case other =>
              throw new EvalException(
                  s"invalid boolean value ${other}, should be a boolean",
                  expr.loc
              )
          }
        case TAT.ExprPlaceholderDefault(defaultVal, optVal, _, _) =>
          // ~{default="foo" optional_value}
          inner(optVal, nestedCtx.advanceState(Some(ExprState.InPlaceholder))) match {
            case V_Null => inner(defaultVal, nestedCtx.advanceState(Some(ExprState.InPlaceholder)))
            case other  => other
          }
        case TAT.ExprPlaceholderSep(sep: TAT.Expr, arrayVal: TAT.Expr, _, _) =>
          // ~{sep=", " array_value}
          val sepString = interpolationValueToString(
              inner(sep, nestedCtx.advanceState(Some(ExprState.InPlaceholder))),
              sep.loc
          )
          inner(arrayVal, nestedCtx.advanceState(Some(ExprState.InPlaceholder))) match {
            case V_Array(array) =>
              val elements: Vector[String] = array.map(interpolationValueToString(_, expr.loc))
              V_String(elements.mkString(sepString))
            case other =>
              throw new EvalException(s"invalid array value ${other}, should be a string", expr.loc)
          }

        case TAT.ExprIfThenElse(cond, tBranch, fBranch, _, loc) =>
          // if (x == 1) then "Sunday" else "Weekday"
          inner(cond, nestedCtx) match {
            case V_Boolean(true)  => inner(tBranch, nestedCtx)
            case V_Boolean(false) => inner(fBranch, nestedCtx)
            case _ =>
              throw new EvalException(s"condition is not boolean", loc)
          }

        case TAT.ExprAt(collection, index, _, loc) =>
          // Access an array element at [index: Int] or map value at [key: K]
          val collectionVal = inner(collection, nestedCtx)
          val indexVal = inner(index, nestedCtx)
          (collectionVal, indexVal) match {
            case (V_Array(av), V_Int(n)) if n < av.size =>
              av(n.toInt)
            case (V_Array(av), V_Int(n)) =>
              val arraySize = av.size
              throw new EvalException(
                  s"array access out of bounds (size=${arraySize}, element accessed=${n})",
                  loc
              )
            case (_: V_Array, _) =>
              throw new EvalException(s"array access requires an array and an integer", loc)
            case (V_Map(value), key) =>
              value
                .collectFirst {
                  case (k, v) if k == key => v
                }
                .getOrElse(
                    throw new EvalException(
                        s"map ${value} does not contain key ${key}",
                        loc
                    )
                )
            case _ =>
              throw new EvalException(
                  s"Invalid array/map ${collectionVal} and/or index ${indexVal}"
              )
          }

        case TAT.ExprGetName(TAT.ExprIdentifier(id, idType, _), fieldName, _, _)
            if nestedCtx.bindings.contains(s"${id}.${fieldName}") =>
          // These cases are a bit of a hack - they enable resolution of a fully-qualified name
          // when the LHS is an identifier, rather than having to first evaluate the LHS expression.
          // In practice, this can be used by a runtime engine to simplify call inputs/outputs - e.g.
          // if task Bar refers to the output of task Foo (say Foo.result), rather than having to make
          // the Foo call object (or some serialized version thereof) avaialble to Bar, it can just
          // expose an output parameter "Foo.result".
          val fqnResolutionAllowed = TypeUtils.unwrapOptional(idType) match {
            case _: WdlTypes.T_Pair if Set("left", "right").contains(fieldName) => true
            case WdlTypes.T_Struct(_, members) if members.contains(fieldName)   => true
            case WdlTypes.T_Call(_, output) if output.contains(fieldName)       => true
            // there's no way to guarantee the resolution is valid for these types:
            case WdlTypes.T_Object => true
            case WdlTypes.T_Any    => true
            case _                 => false
          }
          if (fqnResolutionAllowed) {
            nestedCtx.bindings(s"${id}.${fieldName}")
          } else {
            throw new EvalException(
                s"""'${id}.${fieldName}' is present in the evaluation context, but fully-qualified name 
                   |resolution is not allowed for identifier of type ${idType}""".stripMargin
                  .replaceAll("\n", " ")
            )
          }

        case TAT.ExprGetName(e: TAT.Expr, fieldName, _, loc) =>
          // Evaluate the expression, then access the field
          val ev = inner(e, nestedCtx)
          exprGetName(ev, fieldName, loc)

        case TAT.ExprApply(funcName, _, elements, _, loc) =>
          // Apply a standard library function (including built-in operators)
          // to arguments. For example:
          //   1 + 1
          //   read_int("4")
          val funcArgs = elements.map(e => inner(e, nestedCtx))
          standardLibrary.call(funcName, funcArgs, loc, nestedCtx.exprState)

        case other =>
          throw new Exception(s"sanity: expression ${other} not implemented")
      }
      validate(v, expr.loc)
      v
    }
    inner(expr, ctx)
  }

  // public entry points
  //
  def applyExpr(expr: TAT.Expr, bindings: Bindings[String, V] = WdlValueBindings.empty): V = {
    apply(expr, Context(bindings))
  }

  /**
    * Coerces the result value to the correct type.
    * For example, an expression like:
    *   Float x = "3.2"
    * requires casting from string to float
    * @param expr expression
    * @param wdlType allowed type
    * @param bindings context
    * @return
    */
  def applyExprAndCoerce(expr: TAT.Expr, wdlType: WdlTypes.T, bindings: Bindings[String, V]): V = {
    val value = applyExpr(expr, bindings)
    Coercion.coerceTo(wdlType, value, expr.loc, allowNonstandardCoercions)
  }

  def applyExprAndCoerce(expr: TAT.Expr,
                         wdlTypes: Vector[WdlTypes.T],
                         bindings: Bindings[String, V]): V = {
    val value = applyExpr(expr, bindings)
    Coercion.coerceToFirst(wdlTypes, value, expr.loc, allowNonstandardCoercions)
  }

  // Evaluate all the declarations and return a Context
  def applyPrivateVariables(
      decls: Vector[TAT.PrivateVariable],
      bindings: Bindings[String, V] = WdlValueBindings.empty
  ): Bindings[String, V] = {
    decls.foldLeft(bindings) {
      case (accu: Bindings[String, V], TAT.PrivateVariable(name, wdlType, expr, loc)) =>
        val ctx = Context(accu)
        val value = apply(expr, ctx)
        val coerced = Coercion.coerceTo(wdlType, value, loc, allowNonstandardCoercions)
        accu.add(name, coerced)
      case (_, ast) =>
        throw new Exception(s"Cannot evaluate element ${ast.getClass}")
    }
  }

  private def validateConst(value: V, loc: SourceLocation): Unit = {
    val fs: Option[FileSource] =
      try {
        EvalUtils.unwrapOptional(value) match {
          case V_File(path)      => Some(fileResovler.resolve(path))
          case V_Directory(path) => Some(fileResovler.resolveDirectory(path))
          case _                 => None
        }
      } catch {
        case t: Throwable => throw new EvalException(t.getMessage, loc)
      }
    fs.foreach {
      case _: LocalFileSource =>
        throw new EvalException(s"Path ${fs} is not constant", loc)
      case _ => ()
    }
  }

  /**
    * Evaluates a constant expression. Throws an exception if it isn't a constant.
    * @param expr the expression to evaluate
    * @return the value
    * @example
    * task foo {
    *   command {
    *       # create file mut.vcf
    *   }
    *   output {
    *     File mutations = "mut.vcf"
    *   }
    * }
    *
    * File 'mutations' is not a constant output. It is generated,
    * read from disk, and uploaded to the platform.  A file can't
    * have a constant string as an input, this has to be a dnanexus
    * link.
    */
  def applyConst(expr: TAT.Expr): V = {
    try {
      apply(expr, Context.empty, validateConst)
    } catch {
      case t: Throwable =>
        throw new EvalException(
            s"Expression cannot be evaluated without the runtime context: ${t.getMessage}",
            expr.loc
        )
    }
  }

  /**
    * Coerces the result value to the correct type.
    * For example, an expression like:
    *   Float x = "3.2"
    * requires casting from string to float
    * @param expr expression
    * @param wdlType allowed type
    * @return
    */
  def applyConstAndCoerce(expr: TAT.Expr, wdlType: WdlTypes.T): V = {
    val value = applyConst(expr)
    val coerced = Coercion.coerceTo(wdlType, value, expr.loc, allowNonstandardCoercions)
    validateConst(coerced, expr.loc)
    coerced
  }

  def applyConstAndCoerce(expr: TAT.Expr, wdlTypes: Vector[WdlTypes.T]): V = {
    val value = applyConst(expr)
    val coerced = Coercion.coerceToFirst(wdlTypes, value, expr.loc, allowNonstandardCoercions)
    validateConst(coerced, expr.loc)
    coerced
  }

  /**
    * Determines if `expr` is a constant expression - i.e. it can be
    * evaluated without any Context.
    * @param expr the expression
    * @return
    */
  def isConst(expr: TAT.Expr): Boolean = {
    try {
      applyConst(expr)
      true
    } catch {
      case _: EvalException => false
    }
  }

  /**
    * Determines if `expr` is a constant expression - i.e. it can be
    * evaluated without any Context - and is coercible to `wdlType`
    * @param expr the expression
    * @param wdlType the type requirement
    * @return
    */
  def isConst(expr: TAT.Expr, wdlType: WdlTypes.T): Boolean = {
    try {
      applyConstAndCoerce(expr, wdlType)
      true
    } catch {
      case _: EvalException => false
    }
  }

  /**
    * Given a multi-line string, determine the largest w such that each line
    * begins with at least w whitespace characters.
    * @param s the string to trim
    * @param ignoreEmptyLines ignore empty lines
    * @param lineSep character to use to separate lines in the returned String
    * @return tuple (lineOffset, colOffset, trimmedString) where lineOffset
    *  is the number of lines trimmed from the beginning of the string,
    *  colOffset is the number of whitespace characters trimmed from the
    *  beginning of the line containing the first non-whitespace character,
    *  and trimmedString is `s` with all all prefix and suffix whitespace
    *  trimmed, as well as `w` whitespace characters trimmed from the
    *  beginning of each line.
    *  @example
    *    val s = "   \n  hello\n   goodbye\n "
    *    stripLeadingWhitespace(s, false) => (1, 1, "hello\n  goodbye\n")
    *     stripLeadingWhitespace(s, true) => (1, 2, "hello\n goodbye")
    */
  private def stripLeadingWhitespace(
      s: String,
      ignoreEmptyLines: Boolean = true,
      lineSep: String = System.lineSeparator()
  ): String = {
    val lines = s.split("\r\n?|\n")
    val wsRegex = "^([ \t]*)$".r
    val nonWsRegex = "^([ \t]*)(.+)$".r
    val (_, content) = lines.foldLeft((0, Vector.empty[(String, String)])) {
      case ((lineOffset, content), wsRegex(txt)) =>
        if (content.isEmpty) {
          (lineOffset + 1, content)
        } else if (ignoreEmptyLines) {
          (lineOffset, content)
        } else {
          (lineOffset, content :+ (txt, ""))
        }
      case ((lineOffset, content), nonWsRegex(ws, txt)) => (lineOffset, content :+ (ws, txt))
    }
    if (content.isEmpty) {
      ""
    } else {
      val (whitespace, strippedLines) = content.unzip
      val colOffset = whitespace.map(_.length).min
      val strippedContent = (
          if (colOffset == 0) {
            strippedLines
          } else {
            // add back to each line any whitespace longer than colOffset
            strippedLines.zip(whitespace).map {
              case (line, ws) if ws.length > colOffset => ws.drop(colOffset) + line
              case (line, _)                           => line
            }
          }
      ).mkString(lineSep)
      strippedContent
    }
  }

  // evaluate all the parts of a command section.
  //
  def applyCommand(command: TAT.CommandSection,
                   bindings: Bindings[String, V] = WdlValueBindings.empty): String = {
    val ctx = Context(bindings, ExprState.InString)
    val commandStr = command.parts
      .map { expr =>
        apply(expr, ctx) match {
          case V_Null => ""
          case value  => EvalUtils.formatPrimitive(value, expr.loc)
        }
      }
      .mkString("")
    // strip off common leading whitespace
    stripLeadingWhitespace(commandStr)
  }
}
