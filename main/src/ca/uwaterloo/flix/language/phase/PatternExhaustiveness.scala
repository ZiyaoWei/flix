/*
 * Copyright 2017 Jason Mittertreiner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.Symbol.EnumSym
import ca.uwaterloo.flix.language.ast.Type.{Apply, Arrow, Bool, FTuple, Native, Ref}
import ca.uwaterloo.flix.language.ast.{Type, TypedAst}
import ca.uwaterloo.flix.language.ast.TypedAst.Pattern
import ca.uwaterloo.flix.language.ast.TypedAst.Pattern.Var
import ca.uwaterloo.flix.language.errors.NonExhaustiveMatchError
import ca.uwaterloo.flix.language.phase.PatternExhaustiveness.Exhaustiveness.{Exhaustive, NonExhaustive}
import ca.uwaterloo.flix.language.phase.PatternExhaustiveness.TypeConstructor.{BigInt, Char, Enum, False, Float32, Float64, Int16, Int32, Int64, Int8, Str, True, Tuple, Unit, Wild}
import ca.uwaterloo.flix.language.{CompilationError, GenSym}
import ca.uwaterloo.flix.util.Validation
import ca.uwaterloo.flix.util.Validation._

/**
  * The Pattern Exhaustiveness phase checks pattern matches for exhaustiveness
  *
  * A pattern match is exhaustive if there exists a type correct expression which is not matched by any of the patterns
  * in the pattern match
  *
  * Implemented as described in
  * MARANGET, L. (2007). Warnings for pattern matching. Journal of Functional Programming, 17(3), 387-421. doi:10.1017/S0956796807006223
  *
  * The algorithm works by recursively "peeling off layers" of the pattern match. It check if the outermost type
  * constructors are exhaustive, then if so, recursively calls itself on the arguments. If it finds a pattern which
  * is not exhaustive during a recursive case, it uses it to built up a pattern that is not matched by the full
  * pattern match and returns the result.
  *
  */
object PatternExhaustiveness extends Phase[TypedAst.Root, TypedAst.Root] {

  /**
    * An ADT to make matching Type Constructors easier. We need to
    * support both user created constructors and the implicit built in
    * types. This allows us to handle True, False, Tuples etc in the same
    * way we would a user defined enum.
    */
  sealed trait TypeConstructor
  object TypeConstructor {
    case object True extends TypeConstructor
    case object False extends TypeConstructor
    case object Unit extends TypeConstructor
    case class Tuple(args: List[TypeConstructor]) extends TypeConstructor
    case object BigInt extends TypeConstructor
    case object Int8 extends TypeConstructor
    case object Int16 extends TypeConstructor
    case object Int32 extends TypeConstructor
    case object Int64 extends TypeConstructor
    case object Float32 extends TypeConstructor
    case object Float64 extends TypeConstructor
    case object Str extends TypeConstructor
    case object Char extends TypeConstructor
    case object Wild extends TypeConstructor
    case class Enum(name: String, sym: EnumSym, numArgs: Int, args: List[TypeConstructor]) extends TypeConstructor
  }

  /**
    * A small ADT to track if we've found a non exhaustive pattern
    *
    * Essentially a reverse Optional: we only include a parameter if there is an error
    */
  sealed trait Exhaustiveness
  object Exhaustiveness {
    case object Exhaustive extends Exhaustiveness
    case class NonExhaustive(pat: List[TypeConstructor]) extends Exhaustiveness
  }

  /**
    * Returns an error message if a pattern match is not exhaustive
    */
  def run(root: TypedAst.Root)(implicit flix: Flix): Validation[TypedAst.Root, CompilationError] = {
    implicit val _ = flix.genSym
    val startTime = System.nanoTime()

    for {
      defns <- seqM(root.definitions.map { case (_, v) => Definitions.checkPats(v, root) })
    } yield {
      val currentTime = System.nanoTime()
      val time = root.time.copy(typer = currentTime - startTime)
      root
    }
  }

  object Definitions {
    def checkPats(tast: TypedAst.Declaration.BoundedLattice, root: TypedAst.Root)(implicit genSym: GenSym): Validation[TypedAst.Declaration.BoundedLattice, CompilationError] = tast match {
      case TypedAst.Declaration.BoundedLattice(_, bot0, top0, leq0, lub0, glb0, _) =>
        for {
          _ <- Expressions.checkPats(bot0, root)
          _ <- Expressions.checkPats(top0, root)
          _ <- Expressions.checkPats(leq0, root)
          _ <- Expressions.checkPats(lub0, root)
          _ <- Expressions.checkPats(glb0, root)
        } yield tast
    }

    def checkPats(tast: TypedAst.Declaration.Definition, root: TypedAst.Root)(implicit genSym: GenSym): Validation[TypedAst.Declaration.Definition, CompilationError] = for {
    _ <- Expressions.checkPats(tast.exp, root)
    } yield tast

    def checkPats(tast: TypedAst.Declaration.Index, root: TypedAst.Root )(implicit genSym: GenSym): TypedAst.Declaration.Index = tast
  }

  object Expressions {
    def checkPats(tast: TypedAst.Expression, root: TypedAst.Root)(implicit genSym: GenSym): Validation[TypedAst.Expression, CompilationError] = {
      tast match {
        case TypedAst.Expression.Match(exp, rules, _, _) =>
          for {
            _ <- checkRules(exp, rules, root)
          } yield tast

        case _ => tast.toSuccess[TypedAst.Expression, CompilationError]
      }
    }

    /**
      * Check that the given rules are exhaustive for the given expression
      *
      * @param root The root of the tree
      * @param exp The expression to check
      * @param rules The rules to check
      * @return
      */
    def checkRules(exp: TypedAst.Expression, rules: List[TypedAst.MatchRule], root: TypedAst.Root): Validation[TypedAst, CompilationError] = {
      findNonMatchingPat(rules.map(r => List(r.pat)), 1, root) match {
        case Exhaustive => root.toSuccess[TypedAst, CompilationError]
        case NonExhaustive(ctors) => NonExhaustiveMatchError(
          rules, prettyPrintCtor(ctors.head), exp.loc).toFailure
      }
    }

    /**
      * Given a list of patterns, computes a pattern vector of size n such
      * that p doesn't match any rule in rules
      *
      * @param rules The rules to check for exhaustion
      * @param n The size of resulting pattern vector
      * @param  root The AST root of the expression
      * @return If no such pattern exists, returns Exhaustive, else returns NonExhaustive(a matching pattern)
      */
    def findNonMatchingPat(rules: List[List[Pattern]], n: Integer, root: TypedAst.Root): Exhaustiveness = {
      if (n==0) {
        if (rules.isEmpty) {
          return NonExhaustive(List.empty[TypeConstructor])
        }
        return Exhaustive
      }

      val sigma = rootCtors(rules)
      val missing = missingFromSig(sigma, root)
      if (missing.isEmpty) {
        /* If the constructors are complete, then we check that the arguments to the constructors and the remaining
         * patterns are complete
         *
         * e.g. If we have
         * enum Option[a] {
         *    case Some(a),
         *    case None
         *  }
         *
         * case Some True =>
         * case Some False =>
         * case None =>
         *
         * {Some, None} is a complete signature, but the "Some" case is exhaustive only if the {True, False} case is
         *
         * exhaustive. So we create a "Specialized" matrix for "Some" with {True, False} as rows and check that.
         */
        val checkAll: List[Exhaustiveness] = sigma.map(c => {
          val res: Exhaustiveness = findNonMatchingPat(specialize(c, rules, root), countCtorArgs(c) + n - 1, root)
          res match {
            case Exhaustive => Exhaustive
            case NonExhaustive(ctors) => NonExhaustive(rebuildPattern(c, ctors))
          }
        })
        checkAll.foldRight(Exhaustive: Exhaustiveness)(mergeExhaustive)

      } else {
        /* If the constructors are not complete, then we will fall to the wild/default case. In that case, we need to
         * check for non matching patterns in the wild/default matrix.
         */
        findNonMatchingPat(defaultMatrix(rules), n - 1, root) match {
          case Exhaustive => Exhaustive
          case NonExhaustive(ctors) => sigma match {
            // If sigma is not empty, pick one of the missing constructors and return it
            case _ :: _ => NonExhaustive(rebuildPattern(missing.head, ctors))
            // Else, prepend a wildcard
            case Nil => NonExhaustive(rebuildPattern(Wild, ctors))
          }
        }
      }
    }

    /**
      * Specialize a matrix of patterns for the Constructor ctor For a constructor of C(r1,...ra) and a matrix of width
      * n, we return a matrix of width n+a-1
      *
      * e.g. If we have
      * enum Option {
      *    case Some a,
      *    case Nothing
      *  }
      *
      * And the pattern matrix
      *
      * case Some True =>
      * case Some False =>
      * case Nothing =>
      *
      * Specializing for Some gives use the matrix
      * True =>
      * False =>
      *
      * Where' we've taken a matrix had made of the arguments to the Some rows. Specializing for Nothing would give
      * an empty matrix as it has no arguments.
      *
      * @param ctor The constructor to specialize for
      * @param rules The rules matrix to specialize
      * @param root The ast root
      * @return The specialized matrix of rules
      */
    def specialize(ctor: TypeConstructor, rules: List[List[Pattern]], root: TypedAst.Root): List[List[TypedAst.Pattern]] = {
      // First figure out how many arguments are needed by the ctor
      val numArgs = countCtorArgs(ctor)

      def specializeRow(pat: List[TypedAst.Pattern], acc: List[List[TypedAst.Pattern]]) = pat.head match {
        // A wild constructor is the same as the constructor
        // with all its arguments as wild
        case a: TypedAst.Pattern.Wild =>
          (List.fill(numArgs)(a) ::: pat.tail) :: acc
        case a: TypedAst.Pattern.Var =>
          (List.fill(numArgs)(a) ::: pat.tail) :: acc

        // If it's a pattern with the constructor that we are
        // specializing for, we break it up into it's arguments
        // If it's not our constructor, we ignore it
        case TypedAst.Pattern.Tag(_, tag, exp, _, _) =>
          ctor match {
            case Enum(name, _, _, _) =>
              if (tag == name) {
                exp match {
                    // The expression varies depending on how many arguments it has, 0 arguments => unit, non zero
                    // => Tuple. If there are arguments, we add them to the matrix
                  case TypedAst.Pattern.Tuple(elms, _, _) =>
                    (elms ::: pat.tail) :: acc
                  case TypedAst.Pattern.Unit(_) =>
                    pat.tail :: acc
                  case _ =>
                    (exp :: pat.tail) :: acc
                }
              } else {
                acc
              }
            case _ => acc
          }
        case TypedAst.Pattern.Tuple(elms, _, _) =>
          if (ctor.isInstanceOf[TypeConstructor.Tuple])  {
            elms :: acc
          } else {
            acc
          }
        // Also handle the non tag constructors
        case p =>
          if (patToCtor(p).getClass == ctor.getClass) {
            (p :: pat.tail) :: acc
          } else acc
      }

      rules.foldRight(List.empty[List[TypedAst.Pattern]])(specializeRow)
    }

    /**
      * Extract a default matrix of width n-1
      *
      * defaultMatrix is called by findNonMatchingPat when the given constructor patterns aren't exhaustive.
      * We then want to ensure that the wild card patterns cover the remaining cases.
      *
      * DefaultMatrix constructs a matrix which only has the patterns that begin with a wildcard, then removes the
      * first wildcard since we know all the patterns start with it.
      *
      * e.g.
      * case Red =>
      * case Blue =>
      * case _ =>
      *
      * The default matrix is
      * _ =>
      *
      */
    def defaultMatrix(rules: List[List[TypedAst.Pattern]]): List[List[TypedAst.Pattern]] = {
      val defaultRow = (pat: List[TypedAst.Pattern], acc: List[List[TypedAst.Pattern]]) => pat.head match {
        // If it's a wild card, we take the rest of the pattern
        case _: TypedAst.Pattern.Wild => pat.tail :: acc
        case _: TypedAst.Pattern.Var => pat.tail :: acc

        // If it's a constructor, we don't include a row
        case _ => acc
      }
      rules.foldRight(List.empty[List[TypedAst.Pattern]])(defaultRow)
    }


    /**
      * Computes the set of constructors that appear at the root of the
      * patterns, skipping out on wilds
      *
      * e.g.
      *
      * Foo(1, 3) => ....
      * Bar(Foo(1,3), 'a', 3) => ....
      * Baz() => ....
      * _ => ...
      *
      * returns
      * Foo
      * Bar
      * Baz
      *
      */
    def rootCtors(rules: List[List[TypedAst.Pattern]]): List[TypeConstructor] = {
      def rootCtor(pat: List[TypedAst.Pattern], pats: List[TypedAst.Pattern]) = pat.head match {
        case _: Pattern.Wild => pats
        case _: Pattern.Var => pats
        case tg: Pattern.Tag => tg :: pats
        case p => p :: pats
      }
     rules.foldRight(List.empty[TypedAst.Pattern])(rootCtor).map(patToCtor)
    }

    /**
      * True if ctors is a complete signature for exp. A complete signature is when all constructors of a type are
      * present. E.g. for
      *
      * enum Color {
      *    case Red,
      *    case Blue,
      *    case Yellow
      *  }
      *
      * {Red, Blue, Yellow} is a complete signature, but {Red, Blue} is not. Additionally, {Red, Blue, _} is also not
      * If the constructors are a complete signature, then they are exhaustive for the type, and we just have to
      * check that their arguments are also exhaustive
      *
      * Wildcards are exhaustive, but we need to do some additional checking in that case (@see DefaultMatrix)
      *
      * @param ctors The ctors that we match with
      * @param root Root of the expression tree
      * @return
      */
    def missingFromSig(ctors: List[TypeConstructor], root: TypedAst.Root): List[TypeConstructor] = {
      // Enumerate all the constructors that we need to cover
      def getAllCtors(x: TypeConstructor, xs: List[TypeConstructor]) = x match {
        // For built in constructors, we can add all the options since we know them a priori
        case TypeConstructor.True  => TypeConstructor.True :: TypeConstructor.False :: xs
        case TypeConstructor.False => TypeConstructor.True :: TypeConstructor.False :: xs
        case TypeConstructor.Unit  => TypeConstructor.Unit :: xs
        case a:TypeConstructor.Tuple => a :: xs

        // For Enums, we have to figure out what base enum is, then look it up in the enum definitions to get the
        // other enums
        case TypeConstructor.Enum(_, sym, _, _) => {
          root.enums.get(sym).get.cases.map(x => TypeConstructor.Enum(x._1, sym, countTypeArgs(x._2.tpe), List.empty[TypeConstructor]))}.toList ::: xs

        /* For numeric types, we consider them as "infinite" types union
         * Int = ...| -1 | 0 | 1 | 2 | 3 | ...
         * The only way we get a match is through a wild. Technically, you could, for example, cover a Char by
         * having a case for [0 255], but we'll ignore that case for now
         */
        case _ => TypeConstructor.Wild :: xs
      }
      val expCtors = ctors.foldRight(List.empty[TypeConstructor])(getAllCtors)
      /* We cover the needed constructors if there is a wild card in the
       * root constructor set, or if we match every constructor for the
       * expression
       */
      expCtors.foldRight(List.empty[TypeConstructor])((x, xs) => if (ctors.exists(y => sameCtor(x, y))) xs else x :: xs)
    }

    /**
      * Gets the number of arguments for a constructor, takes into account the "fake constructors"
      *
      * @param ctor The constructor to get from
      * @return The number of arguments for the constructor
      */
    def countCtorArgs(ctor: TypeConstructor): Int = ctor match {
      case True => 0
      case False => 0
      case Unit => 0
      case Tuple(args) => args.size
      case BigInt => 0
      case Int8 => 0
      case Int16 => 0
      case Int32 => 0
      case Int64 => 0
      case Float32 => 0
      case Float64 => 0
      case Str => 0
      case Char => 0
      case Wild => 0
      case Enum(_, _, numArgs, _) => numArgs
    }

    def countTypeArgs(tpe: Type): Int = tpe match {
      case Type.Var(id, kind) => 0
      case Type.Unit => 0
      case Bool => 0
      case Type.Char => 0
      case Type.Float32 => 0
      case Type.Float64 => 0
      case Type.Int8 => 0
      case Type.Int16 => 0
      case Type.Int32 => 0
      case Type.Int64 => 0
      case Type.BigInt => 0
      case Type.Str => 0
      case Native => 0
      case Ref => 0
      case Arrow(length) => length
      case FTuple(length) => length
      case Type.Enum(sym, kind) => 0
      case Apply(t, ts) => countTypeArgs(t)
    }

    /**
      * Pretty print a constructor
      *
      * @param ctor The TypeConstructor to print
      * @return A human readable string of the constructor
      */
    def prettyPrintCtor(ctor: TypeConstructor): String = ctor match {
      case True => "True"
      case False => "False"
      case Unit => "Unit"
      case Tuple(args) => "(" + args.foldRight("")((x, xs) => if (xs == "") prettyPrintCtor(x) + xs else prettyPrintCtor(x) + ", " + xs) + ")"
      case BigInt => "BigInt"
      case Int8 => "Int8"
      case Int16 => "Int16"
      case Int32 => "Int32"
      case Int64 => "Int64"
      case Float32 => "Float32"
      case Float64 => "Float64"
      case Str => "Str"
      case Char => "Char"
      case Wild => "_"
      case Enum(name, _, num_args, args) => if (num_args == 0 ) name else name + prettyPrintCtor(Tuple(args))
    }


    /**
      * Checks if two TypeConstructors refers to the same constructor.
      *
      * @param c1 First constructor to compare
      * @param c2 Second constructor to compare
      * @return True if they are the same constructor
      */
    def sameCtor(c1: TypeConstructor, c2: TypeConstructor): Boolean = (c1,c2) match {
      // Two enums are the same constructor if they have the same name and enum sym
      case (TypeConstructor.Enum(n1, s1, _, _), TypeConstructor.Enum(n2, s2, _, _)) => n1 == n2 && s1 == s2
      // Everything else is the same constructor if they are the same type
      case (a, b) =>  a.getClass == b.getClass;
    }

    def patToCtor(pattern: TypedAst.Pattern): TypeConstructor = pattern match {
      case Pattern.Wild(_, _) => TypeConstructor.Wild
      case Var(_, _, _) => TypeConstructor.Wild
      case Pattern.Unit(_) => TypeConstructor.Unit
      case Pattern.True(_) => TypeConstructor.True
      case Pattern.False(_) => TypeConstructor.False
      case Pattern.Char(_, _) => TypeConstructor.Char
      case Pattern.Float32(_, _) => TypeConstructor.Float32
      case Pattern.Float64(_, _) => TypeConstructor.Float64
      case Pattern.Int8(_, _) => TypeConstructor.Int8
      case Pattern.Int16(_, _) => TypeConstructor.Int16
      case Pattern.Int32(_, _) => TypeConstructor.Int32
      case Pattern.Int64(_, _) => TypeConstructor.Int64
      case Pattern.BigInt(_, _) => TypeConstructor.BigInt
      case Pattern.Str(_, _) => TypeConstructor.Str
      case Pattern.Tag(sym, tag, pat, tpe, _) => TypeConstructor.Enum(tag, sym, countTypeArgs(tpe), pat match {
        case Pattern.Unit(_) => List.empty[TypeConstructor]
        case Pattern.Tuple(elms, _, _) => elms.map(patToCtor)
        case a => List(patToCtor(a))
      })
      case Pattern.Tuple(elms, _, _) => TypeConstructor.Tuple(elms.map(patToCtor))
    }

    /**
      * Adds a TypeConstructor to a list of TypeConstructors, using up items in the list if it requires arguments
      *
      * e.g. rebuildPatter(Foo(a,b), [1,2,3]) => [Foo(1,2), 3]
      *
      * @param tc The type constructor to add
      * @param lst The list to add to
      * @return The new list
      */
    def rebuildPattern(tc: TypeConstructor, lst : List[TypeConstructor]): List[TypeConstructor] = tc match {
      case Tuple(args) => Tuple(lst.take(args.size)) :: lst.drop(args.size)
      case Enum(name, sym, numArgs, _) => Enum(name, sym, numArgs,
        if (numArgs > lst.size) {
          lst.take(lst.size) ::: List.fill(numArgs)(TypeConstructor.Wild)
        } else {
          lst.take(numArgs)
        }) :: lst.drop(numArgs)
      case a => a :: lst
    }

    /**
      * Flatten/join for Exhaust. Accumulates failures if they are there.
      *
      * @param x The Exhasutiveness under consideration
      * @param acc The Exhaustiveness accumulated so far
      * @return The merged result
      */
    def mergeExhaustive(x: Exhaustiveness, acc: Exhaustiveness): Exhaustiveness =
        (x, acc) match {
          case (Exhaustive, Exhaustive) => Exhaustive
          case (a: NonExhaustive, _) => a
          case (_, a: NonExhaustive) => a
        }
  }
}
