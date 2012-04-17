package de.fosd.typechef.typesystem

import de.fosd.typechef.parser.c._
import de.fosd.typechef.featureexpr._
import de.fosd.typechef.conditional._

/**
 * checks an AST (from CParser) for type errors (especially dangling references)
 *
 * performs type checking in a single tree-walk, uses lookup functions from various traits
 *
 * @author kaestner
 *
 */

trait CTypeSystem extends CTypes with CEnv with CDeclTyping with CTypeEnv with CExprTyping with CBuiltIn {


    def typecheckTranslationUnit(tunit: TranslationUnit, featureModel: FeatureExpr = FeatureExpr.base): Unit = {
        assert(tunit != null, "cannot type check Translation Unit, tunit is null")
        checkTranslationUnit(tunit, featureModel, InitialEnv)
    }


    private[typesystem] def checkTranslationUnit(tunit: TranslationUnit, featureExpr: FeatureExpr, initialEnv: Env): Env = {
        var env = initialEnv
        addEnv(tunit, env)
        for (Opt(f, e) <- tunit.defs) {
            env = checkExternalDef(e, featureExpr and f, env)
        }
        env
    }

    private def checkExternalDef(externalDef: ExternalDef, featureExpr: FeatureExpr, env: Env): Env = {
        addEnv(externalDef, env)
        checkingExternal(externalDef)
        externalDef match {
            case _: EmptyExternalDef => env
            case _: Pragma => env //ignore
            case _: AsmExpr => env //ignore
            case e: TypelessDeclaration => assertTypeSystemConstraint(false, featureExpr, "will not occur " + e, e); env //ignore
            case d: Declaration =>
                addDeclarationToEnvironment(d, featureExpr, env)
            case fun@FunctionDef(specifiers, declarator, oldStyleParameters, stmt) =>
                val (funType, newEnv) = checkFunction(specifiers, declarator, oldStyleParameters, stmt, featureExpr, env)
                typedFunction(fun, funType, featureExpr)
                newEnv
        }
    }


    private def checkFunction(specifiers: List[Opt[Specifier]], declarator: Declarator, oldStyleParameters: List[Opt[OldParameterDeclaration]], stmt: CompoundStatement, featureExpr: FeatureExpr, env: Env): (Conditional[CType], Env) = {
        val funType = getFunctionType(specifiers, declarator, oldStyleParameters, featureExpr, env).simplify(featureExpr)
        //structs in signature defined?
        funType.mapf(featureExpr, (f, t) => t.toValue match {
            case CFunction(params, ret) =>
                checkStructs(ret, f, env, declarator)
                params.map(checkStructs(_, f, env, declarator))
            case _ =>
                issueTypeError(Severity.Crash, f, "not a function", declarator)
        })

        val expectedReturnType = funType.map(t => t.asInstanceOf[CFunction].ret).simplify(featureExpr)
        val kind = KDefinition

        //redeclaration?
        checkRedeclaration(declarator.getName, funType, featureExpr, env, declarator, kind)

        //add type to environment for remaining code
        val newEnv = env.addVar(declarator.getName, featureExpr, funType, kind, env.scope)

        //check body (add parameters to environment)
        val innerEnv = newEnv.addVars(parameterTypes(declarator, featureExpr, env.incScope()), KDeclaration, env.scope + 1).setExpectedReturnType(expectedReturnType)
        getStmtType(stmt, featureExpr, innerEnv) //ignore changed environment, to enforce scoping!
        checkTypeFunction(specifiers, declarator, oldStyleParameters, featureExpr, env)

        //check actual return type against declared return type
        //TODO check that something was returned at all

        (funType, newEnv)
    }


    private def checkRedeclaration(name: String, ctype: Conditional[CType], fexpr: FeatureExpr, env: Env, where: AST, kind: DeclarationKind) {

        val prevTypes: Conditional[(CType, DeclarationKind, Int)] = env.varEnv.lookup(name)

        ConditionalLib.mapCombinationF(ctype, prevTypes, fexpr, (f: FeatureExpr, newType: CType, prev: (CType, DeclarationKind, Int)) => {
            if (!isValidRedeclaration(normalize(newType), kind, env.scope, normalize(prev._1), prev._2, prev._3))
                reportTypeError(f, "Invalid redeclaration/redefinition of " + name +
                    " (was: " + prev._1 + ":" + kind + ":" + env.scope +
                    ", now: " + newType + ":" + prev._2 + ":" + prev._3 + ")",
                    where, Severity.RedeclarationError)
        })
    }

    /**
     *
     */
    private def isValidRedeclaration(newType: CType, newKind: DeclarationKind, newScope: Int, prevType: CType, prevKind: DeclarationKind, prevScope: Int): Boolean = {
        if (prevType.isUnknown) return true; //not previously defined => everything's fine

        //scopes
        if (newScope > prevScope) return true; //always fine

        (newType, prevType) match {
            //two prototypes
            case (CPointer(CFunction(newParam, newRet)), CPointer(CFunction(prevParam, prevRet))) if (newKind == KDeclaration && prevKind == KDeclaration) =>
                //must have same return type and same parameters (for common parameters)
                return (newRet == prevRet) && (newParam.zip(prevParam).forall(x => x._1 == x._2))

            //function overriding a prototype or vice versa
            case (CPointer(CFunction(_, _)), CPointer(CFunction(_, _))) if ((newKind == KDefinition && prevKind == KDeclaration) || (newKind == KDeclaration && prevKind == KDefinition)) =>
                //must have the exact same type
                return newType == prevType
            case _ =>
        }


        //global variables
        if (newScope == 0 && prevScope == 0 && (newKind == KDeclaration || newKind == KDefinition) && prevKind == KDeclaration) {
            //valid if exact same type
            return newType.toValue == prevType.toValue
        }

        //local variables (scope>0) may never be redeclared
        //function definitions may never be redeclared
        false
    }


    private def checkInitializer(initExpr: Expr, expectedType: Conditional[CType], featureExpr: FeatureExpr, env: Env): Unit = {
        val foundType = getExprType(initExpr, featureExpr, env)
        ConditionalLib.mapCombinationF(foundType, expectedType, featureExpr, {
            (f, ft: CType, et: CType) => if (f.isSatisfiable() && !coerce(et, ft) && !ft.isUnknown)
                issueTypeError(Severity.OtherError, f, "incorrect initializer type. expected " + et + " found " + ft, initExpr)
        })
    }


    private def addDeclarationToEnvironment(d: Declaration, featureExpr: FeatureExpr, oldEnv: Env): Env = {
        var env = oldEnv
        //declared struct?
        env = env.updateStructEnv(addStructDeclarationToEnv(d, featureExpr, env))
        //declared enums?
        env = env.updateEnumEnv(addEnumDeclarationToEnv(d.declSpecs, featureExpr, env.enumEnv, d.init.isEmpty))
        //declared typedefs?
        env = env.addTypedefs(recognizeTypedefs(d, featureExpr, env))

        val vars = getDeclaredVariables(d, featureExpr, env, checkInitializer)

        //check redeclaration
        for (v <- vars)
            checkRedeclaration(v._1, v._3, v._2, env, d, v._4)

        //add declared variables to variable typing environment and check initializers
        env = env.addVars(vars, env.scope)



        //check array initializers
        checkArrayExpr(d, featureExpr, env: Env)
        checkTypeDeclaration(d, featureExpr, env)

        env
    }

    private def checkInitializers(d: Declaration, featureExpr: FeatureExpr, env: Env) {
        for (Opt(initFeature, init) <- d.init) init match {
            case InitDeclaratorE(_, _, expr) =>
        }

    }

    private def checkArrayExpr(d: Declaration, featureExpr: FeatureExpr, env: Env) {
        //environment correct? or must be interleaved with reading declared variables
        for (Opt(initFeature, init) <- d.init)
            for (Opt(extFeature, ext) <- init.declarator.extensions)
                ext match {
                    case DeclArrayAccess(Some(expr)) =>
                        performExprCheck(expr, isScalar, {
                            c => "expected scalar array size, found " + c
                        }, featureExpr and initFeature and extFeature, env)
                    case _ =>
                }
    }

    /**
     * returns a type and a changed environment for subsequent statements
     *
     * most statements do not have types; type information extracted from sparse (evaluate.c)
     */
    def getStmtType(stmt: Statement, featureExpr: FeatureExpr, env: Env): (Conditional[CType], Env) = {
        def checkStmtF(stmt: Statement, newFeatureExpr: FeatureExpr) = getStmtType(stmt, newFeatureExpr, env)
        def checkStmt(stmt: Statement) = checkStmtF(stmt, featureExpr)
        def checkCStmtF(stmt: Conditional[Statement], newFeatureExpr: FeatureExpr) = stmt.mapf(newFeatureExpr, {
            (f, t) => checkStmtF(t, f)
        })
        def checkCStmt(stmt: Conditional[Statement]) = checkCStmtF(stmt, featureExpr)
        def checkOCStmt(stmt: Option[Conditional[Statement]]) = stmt map checkCStmt

        def expectScalar(expr: Expr, ctx: FeatureExpr = featureExpr) = checkExprX(expr, isScalar, {
            c => "expected scalar, found " + c
        }, ctx)
        def expectIntegral(expr: Expr, ctx: FeatureExpr = featureExpr) = checkExprX(expr, isIntegral, {
            c => "expected int, found " + c
        }, ctx)
        //        def checkFunctionCall(call: PostfixExpr) = checkExpr(call, !_.isUnknown, {ct => "cannot resolve function call, found " + ct})
        //        def checkIdentifier(id: Id) = checkExpr(id, !_.isUnknown, {ct => "identifier " + id.name + " unknown: " + ct})
        def checkExpr(expr: Expr) = checkExprF(expr, featureExpr)
        //expect an expression or a RangeExpression
        def checkExprWithRange(expr: Expr) = expr match {
            case RangeExpr(from, to) => checkExpr(from); checkExpr(to)
            case e => checkExprF(e, featureExpr)
        }
        def checkExprF(expr: Expr, ctx: FeatureExpr) = checkExprX(expr, !_.isUnknown, {
            ct => "cannot resolve expression, found " + ct
        }, ctx)
        def checkExprX(expr: Expr, check: CType => Boolean, errorMsg: CType => String, featureExpr: FeatureExpr) =
            performExprCheck(expr, check, errorMsg, featureExpr, env)
        def nop = (One(CVoid()), env) //(One(CUnknown("no type for " + stmt)), env)

        addEnv(stmt, env)

        stmt match {
            case CompoundStatement(innerStmts) =>
                //get a type of every inner feature, propagate environments between siblings, collect OptList of types (with one type for every statement, under the same conditions)
                var innerEnv = env.incScope()
                val typeOptList: List[Opt[Conditional[CType]]] =
                    for (Opt(stmtFeature, innerStmt) <- innerStmts) yield {
                        val (stmtType, newEnv) = getStmtType(innerStmt, featureExpr and stmtFeature, innerEnv)
                        innerEnv = newEnv
                        Opt(stmtFeature, stmtType)
                    }

                //return last type
                val lastType: Conditional[Option[Conditional[CType]]] = ConditionalLib.lastEntry(typeOptList)
                val t: Conditional[CType] = lastType.mapr({
                    case None => One(CVoid())
                    case Some(ctype) => ctype
                }) simplify (featureExpr);

                //return original environment, definitions don't leave this scope
                (t, env)


            case ExprStatement(expr) =>
                //expressions do not change the environment
                (checkExpr(expr), env)

            case DeclarationStatement(d) =>
                val newEnv = addDeclarationToEnvironment(d, featureExpr, env)
                checkTypeDeclaration(d, featureExpr, newEnv)
                (One(CVoid()), newEnv)

            case NestedFunctionDef(_, spec, decl, oldSP, stmt) =>
                (One(CVoid()), checkFunction(spec, decl, oldSP, stmt, featureExpr, env)._2)

            case WhileStatement(expr, stmt) => expectScalar(expr); checkCStmt(stmt); nop //spec
            case DoStatement(expr, stmt) => expectScalar(expr); checkCStmt(stmt); nop //spec
            case ForStatement(expr1, expr2, expr3, stmt) =>
                if (expr1.isDefined) checkExpr(expr1.get)
                if (expr2.isDefined) expectScalar(expr2.get) //spec
                if (expr3.isDefined) checkExpr(expr3.get)
                checkCStmt(stmt)
                nop
            //case GotoStatement(expr) => checkExpr(expr) TODO check goto against labels
            case r@ReturnStatement(mexpr) =>
                if (assertTypeSystemConstraint(env.expectedReturnType.isDefined, featureExpr, "return statement outside a function? " + mexpr, r)) {
                    val expectedReturnType = env.expectedReturnType.get
                    mexpr match {

                        case None =>
                            if (expectedReturnType map (_ == CVoid()) exists (!_))
                                issueTypeError(Severity.OtherError, featureExpr, "no return expression, expected type " + expectedReturnType, r)
                        case Some(expr) =>
                            val foundReturnType = getExprType(expr, featureExpr, env)
                            ConditionalLib.mapCombinationF(expectedReturnType, foundReturnType, featureExpr,
                                (fexpr: FeatureExpr, etype: CType, ftype: CType) =>
                                    if (!coerce(etype, ftype) && !ftype.isUnknown)
                                        issueTypeError(Severity.OtherError, fexpr, "incorrect return type, expected " + etype + ", found " + ftype, expr))
                    }
                }
                nop

            case CaseStatement(expr, stmt) => checkExprWithRange(expr); checkOCStmt(stmt); nop
            case IfStatement(expr, tstmt, elifstmts, estmt) =>
                expectScalar(expr) //spec
                checkCStmt(tstmt)
                for (Opt(elifFeature, ElifStatement(elifExpr, elifStmt)) <- elifstmts) {
                    expectScalar(elifExpr, featureExpr and elifFeature)
                    checkCStmtF(elifStmt, featureExpr and elifFeature)
                }
                checkOCStmt(estmt)
                nop

            case SwitchStatement(expr, stmt) => expectIntegral(expr); checkCStmt(stmt); nop //spec
            case DefaultStatement(stmt) => checkOCStmt(stmt); nop

            case EmptyStatement() => nop
            case ContinueStatement() => nop
            case BreakStatement() => nop

            case GotoStatement(_) => nop //TODO check goto against labels
            case LabelStatement(_, _) => nop
            case LocalLabelDeclaration(ids) => nop
        }
    }


    private def performExprCheck(expr: Expr, check: CType => Boolean, errorMsg: CType => String, context: FeatureExpr, env: Env): Conditional[CType] =
        if (context.isSatisfiable()) {
            val ct = getExprType(expr, context, env).simplify(context)
            ct.mapf(context, {
                (f, c) =>
                    if (!check(c) && !c.isUnknown && !c.isIgnore) reportTypeError(f, errorMsg(c), expr) else c
            })
        } else One(CUnknown("unsatisfiable condition for expression"))


    //
    //    //
    //    //    def checkFunctionCallTargets(source: AST, name: String, callerFeature: FeatureExpr, targets: List[Entry]) = {
    //    //        if (!targets.isEmpty) {
    //    //            //condition: feature implies (target1 or target2 ...)
    //    //            functionCallChecks += 1
    //    //            val condition = callerFeature.implies(targets.map(_.feature).foldLeft(FeatureExpr.base.not)(_.or(_)))
    //    //            if (condition.isTautology(null) || condition.isTautology(featureModel)) {
    //    //                dbgPrintln(" always reachable " + condition)
    //    //                None
    //    //            } else {
    //    //                dbgPrintln(" not always reachable " + callerFeature + " => " + targets.map(_.feature).mkString(" || "))
    //    //                Some(functionCallErrorMessages.get(name) match {
    //    //                    case None => ErrorMsgs(name, List((callerFeature, source)), targets)
    //    //                    case Some(err: ErrorMsgs) => err.withNewCaller(source, callerFeature)
    //    //                })
    //    //            }
    //    //        } else {
    //    //            dbgPrintln("dead")
    //    //            Some(ErrorMsgs.errNoDecl(name, source, callerFeature))
    //    //        }
    //    //    }
    //
    //    //
    //    //
    //    //    def checkFunctionRedefinition(env: LookupTable) {
    //    //        val definitions = env.byNames
    //    //        for ((name, defs) <- definitions) {
    //    //            if (defs.size > 1) {
    //    //                var fexpr = defs.head.feature
    //    //                for (adef <- defs.tail) {
    //    //                    if (!(adef.feature mex fexpr).isTautology(featureModel)) {
    //    //                        dbgPrintln("function " + name + " redefined with feature " + adef.feature + "; previous: " + fexpr)
    //    //                        functionRedefinitionErrorMessages = RedefErrorMsg(name, adef, fexpr) :: functionRedefinitionErrorMessages
    //    //                    }
    //    //                    fexpr = fexpr or adef.feature
    //    //                }
    //    //            }
    //    //        }
    //    //    }
    //    //
    //    //    val checkFunctionCalls: AST ==> Unit = attr {
    //    //        case obj => {
    //    //            // Process the errors of the children of t
    //    //            for (child <- obj.children)
    //    //                checkFunctionCalls(child)
    //    //            obj match {
    //    //            //function call (XXX: PG: not-so-good detection, but will work for typical code).
    //    //                case e@PostfixExpr(Id(name), FunctionCall(_)) => {
    //    //                    //Omit feat2, for typical code a function call is always a function call, even if the parameter list is conditional.
    //    //                    checkFunctionCall(e -> env, e, name, e -> presenceCondition)
    //    //                }
    //    //                case _ =>
    //    //            }
    //    //        }
    //    //    }
    //    //
    //    //
    //    //    def checkFunctionCall(table: LookupTable, source: AST, name: String, callerFeature: FeatureExpr) {
    //    //        val targets: List[Entry] = table.find(name)
    //    //        dbgPrint("function " + name + " found " + targets.size + " targets: ")
    //    //        checkFunctionCallTargets(source, name, callerFeature, targets) match {
    //    //            case Some(newEntry) =>
    //    //                functionCallErrorMessages = functionCallErrorMessages.updated(name, newEntry)
    //    //            case _ => ()
    //    //        }
    //    //    }


    /**
     * check type specifiers in signatures   and declarators
     *
     *
     * currently only checks that TypeDefNames are in scope
     *
     */

    private def checkTypeSpecifiers(specifiers: List[Opt[Specifier]], featureExpr: FeatureExpr, env: Env) =
        for (Opt(f, spec) <- specifiers)
            checkTypeSpecifier(spec, featureExpr and f, env)

    def checkTypeStructDecl(decl: StructDecl, expr: FeatureExpr, env: Env) {}

    def checkTypeStructDeclaration(declaration: StructDeclaration, expr: FeatureExpr, env: Env) {
        checkTypeSpecifiers(declaration.qualifierList, expr, env)
        for (Opt(f, StructDeclarator(decl, _, _)) <- declaration.declaratorList)
            checkTypeDeclarator(decl, expr and f, env)
    }


    def checkTypePointers(pointers: List[Opt[Pointer]], expr: FeatureExpr, env: Env) =
        for (Opt(f, ptr) <- pointers)
            checkTypeSpecifiers(ptr.specifier, expr and f, env)


    def checkTypeDeclaratorExtensions(declExts: List[Opt[DeclaratorExtension]], expr: FeatureExpr, env: Env) =
        for (Opt(f, declExt) <- declExts)
            checkTypeDeclaratorExtension(declExt, expr and f, env)


    def checkTypeParam(declaration: ParameterDeclaration, expr: FeatureExpr, env: Env) = declaration match {
        case PlainParameterDeclaration(specifiers) =>
            checkTypeSpecifiers(specifiers, expr, env)
        case ParameterDeclarationD(specifiers, decl) =>
            checkTypeSpecifiers(specifiers, expr, env)
            checkTypeDeclarator(decl, expr, env)
        case ParameterDeclarationAD(specifiers, abstDecl) =>
            checkTypeSpecifiers(specifiers, expr, env)
            checkTypeAbstractDeclarator(abstDecl, expr, env)
        case VarArgs() =>
    }


    def checkTypeDeclaratorExtension(declExt: DeclaratorExtension, expr: FeatureExpr, env: Env) =
        declExt match {
            case DeclParameterDeclList(params) =>
                for (Opt(f, param) <- params)
                    checkTypeParam(param, expr and f, env)
            case DeclIdentifierList(_) =>
            case DeclArrayAccess(_) =>
        }


    private def checkTypeDeclarator(declarator: Declarator, expr: FeatureExpr, env: Env): Unit =
        declarator match {
            case AtomicNamedDeclarator(pointers, _, extensions) =>
                checkTypePointers(pointers, expr, env)
                checkTypeDeclaratorExtensions(extensions, expr, env)
            case NestedNamedDeclarator(pointers, decl, extensions) =>
                checkTypePointers(pointers, expr, env)
                checkTypeDeclaratorExtensions(extensions, expr, env)
                checkTypeDeclarator(decl, expr, env)

        }

    private def checkTypeAbstractDeclarator(declarator: AbstractDeclarator, expr: FeatureExpr, env: Env): Unit =
        declarator match {
            case AtomicAbstractDeclarator(pointers, extensions: List[Opt[DeclaratorAbstrExtension]]) =>
                checkTypePointers(pointers, expr, env)
                checkTypeDeclaratorExtensions(extensions, expr, env)

            case NestedAbstractDeclarator(pointers, nestedDecl, extensions) =>
                checkTypePointers(pointers, expr, env)
                checkTypeDeclaratorExtensions(extensions, expr, env)
                checkTypeAbstractDeclarator(nestedDecl, expr, env)
        }

    private def checkTypeDeclaration(declaration: Declaration, featureExpr: FeatureExpr, env: Env) {
        if (!declaration.init.isEmpty) //do not check specifiers on headless declarations, usually used as forward declarations
            checkTypeSpecifiers(declaration.declSpecs, featureExpr, env)
        for (Opt(f, init) <- declaration.init)
            checkTypeDeclarator(init.declarator, featureExpr and f, env)
    }

    private def checkTypeOldStyleParam(declaration: OldParameterDeclaration, expr: FeatureExpr, env: Env) = declaration match {
        case d: Declaration => checkTypeDeclaration(d, expr, env)
        case VarArgs() =>
    }


    private def checkTypeFunction(specifiers: List[Opt[Specifier]], declarator: Declarator, oldStyleParam: List[Opt[OldParameterDeclaration]], expr: FeatureExpr, env: Env) {
        checkTypeSpecifiers(specifiers, expr, env)
        checkTypeDeclarator(declarator, expr, env)
        for (Opt(f, osp) <- oldStyleParam)
            checkTypeOldStyleParam(osp, expr and f, env)

    }


    private def checkTypeSpecifier(specifier: Specifier, expr: FeatureExpr, env: Env) {
        specifier match {
            case TypeDefTypeSpecifier(name) =>
                val declExpr = env.typedefEnv.whenDefined(name.name)
                if ((expr andNot declExpr).isSatisfiable)
                    reportTypeError(expr andNot declExpr, "Type " + name.name + " not defined. (defined only in context " + declExpr + ")", specifier, Severity.TypeLookupError)

            case EnumSpecifier(Some(id), None) =>
            // Not checking enums anymore, since they are only enforced by compilers in few cases (those cases are hard to distinguish, gcc is not very close to the standard here)
            //                val declExpr = env.enumEnv.getOrElse(id.name, FeatureExpr.dead)
            //                if ((expr andNot declExpr).isSatisfiable)
            //                    reportTypeError(expr andNot declExpr, "Enum " + id.name + " not defined. (defined only in context " + declExpr + ")", specifier, Severity.TypeLookupError)

            case StructOrUnionSpecifier(isUnion, Some(id), enumerators) =>
                for (Opt(f, enumerator) <- enumerators)
                    checkTypeStructDeclaration(enumerator, expr and f, env)
            // checked at call site (when declaring a variable or calling a function)
            //                val declExpr = env.structEnv.isDefined(id.name, isUnion)
            //                if ((expr andNot declExpr).isSatisfiable)
            //                    reportTypeError(expr andNot declExpr, (if (isUnion) "Union " else "Struct ") + id.name + " not defined. (defined only in context " + declExpr + ")", specifier, Severity.TypeLookupError)

            case StructOrUnionSpecifier(_, None, enumerators) =>
                for (Opt(f, enumerator) <- enumerators)
                    checkTypeStructDeclaration(enumerator, expr and f, env)

            case _ =>
        }

    }


}




