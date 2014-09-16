package scala.tools.nsc.effects

import scala.tools.nsc.Global
import collection.mutable
import scala.reflect.internal.Mode

trait TypeCheckerPlugin extends TypeUtils { // self: EffectChecker =>
  val global: Global

  val domain: EffectDomain {
    val global: TypeCheckerPlugin.this.global.type
  }

  import global._
  import analyzer.{AnalyzerPlugin, Typer, AbsTypeError, SilentResultValue, SilentTypeError}
  import analyzer.ErrorUtils.{issueNormalTypeError, issueTypeError}
  import domain._
  import domain.lattice._

  global.addAnnotationChecker(annotChecker)
  analyzer.addAnalyzerPlugin(typerPlugin)

  // overrides are checked during refChecks
  def pluginIsActive(): Boolean =
    global.phase.id <= currentRun.refchecksPhase.id

  object annotChecker extends AnnotationChecker {

    override def isActive(): Boolean = pluginIsActive()

    // @TODO: make sure we end up here only when checking refinements, members of types. effects of method return
    // types for instance should not be checked here. how? maybe inspect the stack trace to get some evidence.
    def annotationsConform(tpe1: Type, tpe2: Type): Boolean = {
      val e1 = fromAnnotation(tpe1)
      val e2 = fromAnnotation(tpe2)

      val rel1 = relFromAnnotation(tpe1)
      val rel2 = relFromAnnotation(tpe2)

      effectsConform(e1, rel1, e2, rel2)._1
    }

    private def effectsConform(e1: Effect, rel1: List[RelEffect],
                               e2: Effect, rel2: List[RelEffect],
                               visited: Set[Symbol] = Set()): (Boolean, Set[Symbol]) = {
      var localVisited = visited

      def checkRelEff(fun: Symbol) = {
        localVisited += fun
        val resTp = fun.info.finalResultType
        val eFun = fromAnnotation(resTp)
        val relFun = relFromAnnotation(resTp)
        val (b, v) = effectsConform(eFun, relFun, e2, rel2, localVisited)
        localVisited = v
        b
      }

      val res = e1 <= e2 && rel1.forall(r1 => {
        lteRelOne(r1, rel2) || {
          r1 match {
            case RelEffect(ParamLoc(sym), None) if sym.isByNameParam =>
              // by-name parameter references can have any effect
              top <= e2

            case RelEffect(loc, None) =>
              r1.applyMethods.forall(checkRelEff)

            case RelEffect(loc, Some(fun)) =>
              if (localVisited(fun)) true
              else checkRelEff(fun)
          }
        }
      })
      (res, localVisited)
    }


    /**
     * annotations-lub, annotations-glb, for effects in refinements: e.g. if(..) fun1 else fun2, we need
     * lub of the fun's effects
     *
     * In fact, with way "lub" is written in the Scala compiler currently, it's impossible to trigger these
     * methods. First, remember that effect annotations can only appear on return types of methods. Therefore
     * a lub of two effects can only be triggered when computing the lub of two method types, which is only
     * possible if the method appears in a refined type.
     *
     * The type of a term never has an effect annotation, so for instance in
     *
     *   if (cond) t1 else t2
     *
     * the types of t1 and t2 will not have any effect annotations - but they might be refined types with
     * effect-annotated methods.
     *
     * When computing the lub, the compiler first checks if one type is a supertype of all others (elimSub) and
     * keeps that as the resulting LUB. In this case, effect annotations are preserved.
     *
     * Otherwise, it triggers the actual lub computation. Without going into details of "def lub1", it seems that
     * the scala compiler doesn't compute refinements of individual members at all, as shown by this repl transcript
     *
     *   scala> class A; class B
     *   scala> if (cnd) new { def f: A = new A } else new { def f: B = new B }
     *   res2: Object = $anon$1@9aa3f3
     *
     * The type of res2 is Object, even though it could be { def f: Object }
     *
     * Note that there's a workaround: manually giving the expected type. Then the lub computation is not triggered,
     * instead the typer will just verify that both branches of the "if" conform to the expected type
     *
     *   scala> (if (cnd) new { def f: A = new A } else new { def f: B = new B }) : { def f: Object }
     *   res3: AnyRef{def f: Object} = $anon$2@457235
     */

    override def annotationsLub(tp: Type, ts: List[Type]): Type =
      lubOrGlb(tp, ts, joinAll, joinAllRel)

    override def annotationsGlb(tp: Type, ts: List[Type]): Type =
      lubOrGlb(tp, ts, meetAll, meetAllRel)


    // @TODO: should only do something if there are effect annotations in "ts", the method is also triggered
    // if there are other kinds of annotations
    def lubOrGlb(tp: Type, ts: List[Type],
                 combineEff: List[Effect] => Effect,
                 combineRel: List[List[RelEffect]] => List[RelEffect]): Type = {
      val effs = ts.map(tp => fromAnnotation(tp))
      // @TODO: check if rel effects all refer to symbols of the resulting method type, i.e. if lub/glb unify the
      // symbols for dependent method types
      val rels = ts.map(tp => relFromAnnotation(tp))
      val eff = combineEff(effs)
      val rel = combineRel(rels)
      setEffect(tp, eff, rel)
    }

  }

  object typerPlugin extends AnalyzerPlugin {
    /**
     * De-activate this annotation checker for phases after pickling.
     * UnCurry for instance generates classes and type-checks them, this code can confuse the
     * annotation checker (example: the generated class doesn't exist in the `templates` map)
     *
     * need to run in parser: many type completers run at parser phase (see LazyTypeRef in UnPickler),
     * and type completion can trigger typing of arbitrary trees
     */
    override def isActive(): Boolean = pluginIsActive()


    
    /**
     * This map associciates class symbols (module class symbols for objects) with their `Template`
     * tree and the `Typer` instance for typing their body. These are needed for type-and-effect
     * checking primary constructors: their effect also contains field initializers, constructor
     * statements and parent constructor calls.
     * 
     * NOTE: the typer is stored in this HashMap is the one used during `Namers`. It contains in
     * its scope all members that are defined in the template. CAREFUL: that exact same scope is
     * also used in the `ClassInfoType` which is assigned to the class symbol. This means: entering
     * a symbol to that scope will enter it into the `ClassInfoType`! There was a bug related to
     * this fact (before typing and effect-checking the template statements, we entered the `self`
     * symbol into the scope. This turned the `self` symbol into a member of the class, and some
     * `Ident(self)` trees were resolved to the wrong symbol afterwards).
     * 
     * Therefore, the `get` method returns a typer whose context has a fresh, empty scope.
     * 
     * @TODO: use a weak map, or clear it, or use tree attachments, attach (Template, Typer) to the
     * primary constructor DefDef
     */
    object templates {
      private val templates: mutable.Map[Symbol, (Template, Typer)] = mutable.Map()
      def add(cls: Symbol, tpl: Template, tpr: Typer) = {
        templates += (cls -> (tpl, tpr))
      }


      def enterTplSyms(templ: Template, templTyper: Typer) {
        val self1 = templ.self match {
          case vd @ ValDef(_, _, tpt, EmptyTree) =>
            val tpt1 = treeCopy.TypeTree(tpt).setOriginal(tpt) setType vd.symbol.tpe
            copyValDef(vd)(tpt = tpt1, rhs = EmptyTree) setType NoType
        }
        if (self1.name != nme.WILDCARD)
          templTyper.context.scope enter self1.symbol
        templTyper.namer.enterSyms(templ.body)
      }
      
      def get(cls: Symbol): (Template, Typer) = {
        val (templ, tpr) = templates(cls)
        val templTyper = analyzer.newTyper(tpr.context.outer.make(templ, cls, newScope))
        enterTplSyms(templ, templTyper)
        (templ, templTyper)
      }
    }
    
    lazy val ConstrEffTypeDefName = newTypeName("constructorEffect")


    /**
     * We remove all effect annotations from the expected type when type-checking a tree.
     *
     * @TODO: doc why, see session.md
     */
    override def pluginsPt(pt: Type, typer: Typer, tree: Tree, mode: Mode): Type = {
      removeAllEffectAnnotations(pt)
    }

    /**
     * For the `tpt` of a DefDef or ValDef, returns `true` if the tpt was empty, i.e. if
     * the return type of the definition was inferred.
     */
    def tptWasInferred(tpt: Tree): Boolean = tpt.isEmpty || (tpt match {
      case tt @ TypeTree() => tt.wasEmpty
      case _ => false
    })
    
    def expectedIfNotUnchecked(annots: List[AnnotationInfo]): Option[Effect] = {
      if (hasUncheckedAnnotation(annots) && existsEffectAnnotation(annots)) {
        None
      } else {
        Some(fromAnnotationList(annots))
      }
    }

    def constructorEffectAnnotations(constrSym: Symbol, typeDefAnnots: List[AnnotationInfo]): List[AnnotationInfo] = {
      val annotatedSym = if (constrSym.isPrimaryConstructor) {
        val clazz = constrSym.owner
        if (clazz.isModuleClass) clazz.sourceModule else clazz
      } else constrSym
      val symAnnots = annotatedSym.annotations
      if (existsEffectAnnotation(symAnnots))
        symAnnots
      else
        typeDefAnnots
    }

    /**
     * Returns the annotated effect of a constructor, if an effect annotation exists.
     *
     * Primary constructor effects are annotated on the class. Auxiliary constructor effects
     * are annotated on the constructor symbol
     */
    def annotatedConstrEffect(constrSym: Symbol, typeDefAnnots: List[AnnotationInfo]): Option[(Effect, List[RelEffect])] = {
      val annots = constructorEffectAnnotations(constrSym, typeDefAnnots)
      if (existsEffectAnnotation(annots))
        Some(fromAnnotationList(annots), relFromAnnotationList(annots))
      else
        None
    }

    /**
     * 
     */
    def constrEffTypeDefAnnots(constrDef: DefDef, tmpl: Option[Template], typer: Typer, alreadyTyped: Boolean): List[AnnotationInfo] = {

      class MapReferences(symMap: Map[Symbol, Symbol]) extends Transformer {
        override def transform(tree: Tree) = tree match {
          case t if symMap.contains(t.symbol) =>
            gen.mkAttributedRef(symMap(t.symbol))
          case t =>
            super.transform(t)
        }
      }

      tmpl match {
        case Some(Template(_, _, body)) =>
          body collect {
            case td @ TypeDef(_, ConstrEffTypeDefName, _, _) => td
          } match {
            case td :: _ =>
              val paramFields = body collect {
                case vd: ValDef if vd.mods.isParamAccessor => vd
              }
              if (!alreadyTyped) {
                typer.namer.enterSyms(td :: paramFields)
              }
              val fieldSyms = paramFields.map(_.symbol)
              val paramSyms = constrDef.vparamss.flatten.map(_.symbol)
              // trim because fields names have a whitespace at the end.
              assert(fieldSyms.length == paramSyms.length && (fieldSyms, paramSyms).zipped.forall((fs, ps) => fs.name.toString.trim == ps.name.toString.trim), s"$fieldSyms --- $paramSyms ")

              val annots = td.symbol.initialize.annotations
              // TODO: for val params, do they refer to the getters instead of the fieldSyms?
              val mapper = new MapReferences(fieldSyms.zip(paramSyms).toMap).transform(_)
              annots map {
                case AnnotationInfo(atp, args, assocs) => AnnotationInfo(atp, args map mapper, assocs)
              }

            case _ =>
              Nil
          }

        case None => constrDef.rhs match {
          case Block(thisCall :: (td @ TypeDef(_, ConstrEffTypeDefName, _, _)) :: _, _) =>
            if (!alreadyTyped) {
              typer.namer.enterSym(td)
            }
            td.symbol.initialize.annotations

          case _ =>
            Nil
        }
      }
    }

    /**
     * Returns the effect of a primary constructor, composed of the effects of the `constrBody`,
     * the field initializers and the statements in `tpl`.
     *
     * @param constrBody   The body of the primary constructor, can be typed or not
     * @param defTyper     The typer for the constructor body
     * @param templ        The template, can be typed or not
     * @param templTyper   Typer for the template
     * @param typedParents The typed parent trees of the template
     * @param alreadyTyped A flag indicating if `body` and `templ` are already typed or not
     * @param expected     The expected effect. If defined, computeEffect will report errors on effect mismatches.
     */
    def inferPrimaryConstrEff(constrDef: DefDef, defTyper: Typer, templ: Template, templTyper: Typer,
                              typedParents: List[Tree], alreadyTyped: Boolean, expected: Option[Effect]): (Effect, List[RelEffect]) = {
      val constrSym = constrDef.symbol
      
      val constrMismatchMsg = "The effect of the primary constructor does not match the annotated effect."
      val superContextMsg     = Some(constrMismatchMsg + "\nThe mismatch is either due to the super constructor call or an early definition.")
      val fieldContextMsg     = Some(constrMismatchMsg + "\nThe mismatch is due to a field initialization expression.")
      val ownerKindString     = if (constrSym.owner.isModuleClass) "object" else constrSym.owner.keyString // keyString is 'class' for module classes
      val statementContextMsg = Some(constrMismatchMsg + s"\nThe mismatch is due to a statement in the ${ownerKindString} body.")
      val parentContextMsg    = Some(constrMismatchMsg + "\nThe mismatch is due to the initializer of a parent trait.")


      /* constructor effect */
      
      /* We create a copy of the block as a workaround for a bug. The problem is that `Typer.parentTypes` only works
       * correctly for polymorphic parent types with inferred type arguments if the `rhs` block of the primary constructor
       * does not have a type. `parentTypes` creates a copy of the primary constructor body, eliminating the `()` in the
       * primary constructor block, and placing a modified `super` call:
       * 
       *   val cbody1 = treeCopy.Block(cbody, preSuperStats, .. transformSuperCall(superCall) ..)
       * 
       * The `cbody1` is then type-checked, which infers the type arguments for the porymorphic parent. The problem is: if
       * the initial block `cbody` already has a type, then that type is assigned by `treeCopy` to `cbody1`. Since the
       * constructor block ends in `()`, the type of `cbody` is always Unit (if it is defined). The result is surprising
       * error messages ("illegal inheritance from final class Unit").
       * 
       * To avoid this problem we always create a copy of the primary constructor block. This makes sure we don't assign
       * the Unit type to the block reachable through the template.
       */
      val constrBody = constrDef.rhs match {
        case Block(stats, expr) => Block(stats, expr).copyAttrs(constrDef.rhs)
        case _ => constrDef.rhs
      }
      
      val typedConstrBody =
        if (alreadyTyped) constrBody
        else typeCheckRhs(constrBody, defTyper, WildcardType)
      val anfConstrBody = maybeAnf(typedConstrBody, defTyper, WildcardType)

      val relEnv = relEffects(constrSym)

      val rhsEff = domain.computeEffect(anfConstrBody, effectContext(expected, relEnv, defTyper, superContextMsg))

      
      /* field initializers and statements in class body */
      
      val (fieldEffs: Map[Symbol, Effect], statEffs: List[Effect]) = {
          
        import scala.collection.mutable.ListBuffer
        
        // lazy vals don't contribute to the constructor effect
        def keepField(vd: ValDef) =
          !(vd.symbol.isParamAccessor || vd.symbol.isEarlyInitialized || vd.symbol.isLazy)
        
        def fieldTyper(vd: ValDef) =
          analyzer.newTyper(templTyper.context.makeNewScope(vd, vd.symbol))

        def statTyper(stat: Tree) = {
          val statsOwner = templ.symbol orElse constrSym.owner.newLocalDummy(templ.pos)
          analyzer.newTyper(templTyper.context.make(stat, statsOwner))
        }

        val init = (Map[Symbol, Effect](), ListBuffer[Effect]())

        val res = (init /: templ.body) {
          case ((fields, stats), stat) => stat match {
            case imp: Import =>
              // like in typedStats
              imp.symbol.initialize
              if (!imp.symbol.isError) {
                // FIXME: changed makeNewImport to make, was that correct?
                templTyper.context = templTyper.context.make(imp)
              }
              (fields, stats)

            case vd: ValDef if keepField(vd) =>
              lazy val tpr = fieldTyper(vd)
              val fieldSym = vd.symbol
              val typedRhs = if (alreadyTyped) vd.rhs else typeCheckRhs(vd.rhs, tpr, fieldSym.tpe)
              val anfRhs = maybeAnf(typedRhs, tpr, fieldSym.tpe)
              // use templTyper for effectContext, don't force tpr: it's only used for error reporting, so doesn't really matter
              val eff = domain.computeEffect(anfRhs, effectContext(expected, relEnv, templTyper, fieldContextMsg))
              (fields + (fieldSym -> eff), stats)

              
            case s if !s.isDef =>
              lazy val tpr = statTyper(s)
              val typedStat = if (alreadyTyped) s else tpr.typed(s)
              val anfStat = maybeAnf(typedStat, tpr, WildcardType)
              val eff = domain.computeEffect(anfStat, effectContext(expected, relEnv, templTyper, statementContextMsg))
              (fields, stats += eff)

            case _ =>
              (fields, stats)
          }
        }

        (res._1, res._2.toList)
      }
      

      /* initializers of parent traits */
      
      val traitInitEffs = typedParents.tail.map({ parent =>
        // fromAnnotation(parent.tpe.typeSymbol.primaryConstructor.info)
        val traitInit = parent.tpe.typeSymbol.primaryConstructor
        val eff =
          if (traitInit == NoSymbol) lattice.bottom
          else {
            // position important for effect mismatch error messages
            val traitInitApply = atPos(parent.pos)(Apply(gen.mkAttributedRef(traitInit), Nil))
            domain.computeEffect(defTyper.typed(traitInitApply), effectContext(expected, relEnv, defTyper, parentContextMsg))
          }
        (traitInit, eff)
      }).toMap


      /* putting everything together */

      val resEff = adaptInferredPrimaryConstrEffect(constrDef, rhsEff, fieldEffs, statEffs, traitInitEffs)
      // need to check that the effect conforms because `adaptInferredPrimaryConstrEffect` might return a larger effect
      // than the expected effect used for checking the various statements
      val ctx = effectContext(expected, relEnv, templTyper, Some(constrMismatchMsg))
      checkConform(resEff, templ, ctx)
      (resEff, relEnv)
    }

    /**
     * The comment below applies to scala/scala branch "master", i.e. the upcoming 2.11. This template desugaring
     * scheme used to be also in 2.10.x, but it got reverted in PR #2068 for binary compatibility. The differences
     * are that in the old scheme, i.e. in 2.10
     *
     *   - Template parents do NOT have value arguments, only type arguments
     *   - Type checking the parent types does not produce any attachments
     *   - The tree of the primary constructor has the correct super constructor call from the beginning, there
     *     is no `pendingSuperCall`. The value parameters of the super constructor call are already there.
     *   - The side-effect of assigning a type to the pre-super fields is now done by `parentTypes` in the old
     *     scheme (`typedPrimaryConstrBody` in the new scheme)
     *
     *
     * Primary constructors get a very special treatment in parsing, naming and typing. Here's an example:
     *
     * class C(param: Int) extends { val early = this } with D[T](arg) with T { self: ST =>
     *   val field: Int = 10
     *   statement1()
     *   def this() {
     *     this(1)
     *     statement2()
     *   }
     * }
     *
     * After parsing, the class is represented as follows (see def Template` in scala.tools.nsc.ast.Trees)
     *
     * ClassDef(C) { Template(D[T](arg), T) {
     *   // "self" field of template
     *   private val self: ST = <empty>
     *
     *   // "body" list of template
     *   <presuper> private[this] val early = <empty>            // note: this field has an empty tpt and no rhs!
     *   <paramaccessor> private[this] val param: Int = <empty>
     *   def <init>(param: Int) = {                              // note: no return type. methodSig for the constructor will put C.
     *     <presuper> val early = this
     *     <pendingSuperCall> super.<init>()                     // no super constructor arguments yet, just a dummy!
     *     ()
     *   }
     *   val field: Int = 10
     *   statement1()
     *   def <init>(): C {
     *     this.<init>(1)
     *     statement2()
     *   }
     * }}
     *
     * Namer assigns a lazy type to class C which calls classSig, and subsequently templateSig.
     *
     * TemplateSig will invoke `typer.parentTypes(tmepl)`. Typing the parent types has a few side-effects
     *   - For the parent type containing the super constructor argument, the returned tree is the typed
     *     parent type without the arguments. The latter are available as a SuperArgsAttachment.
     *   - The typed parent type trees are discarded, templateSig only keeps the computed types. The typer
     *     will call `parentTypes(templ)` once again to get the typed trees.
     *   - If the superclass has type parameters, it is necessary to type-check the primary constructor
     *     to infer those type arguments. (For trait parents this is not necessary, there the type arguments
     *     need to be specified in source).
     *   - The method `typer.typedPrimaryConstrBody` has a crucial side-effect: for every <presuper> value
     *     in the primary constructor, after computing its type it assigns that type to the corresponding
     *     <presuper> field in the class. If that was not the case, the type completers for these fields would
     *     fail: there would be no tpt and no rhs.
     *   - If the superclass is not polymorphic, the <presuper> fields will not have a type. In this case,
     *     `parentTypes` invokes `typedPrimaryConstrBody` to make this happen.
     *
     * When the class is type-checked, typedTemplate will modify the trees to the following
     *   - Introduce the typed template parents (and remove the value arguments)
     *   - introduce the real super call into the primary constructor using `Trees.PrimarySuperCall` and the
     *     arguments stored in the (type-checked) parent type's `SuperArgsAttachment`.
     *   - the presuper values (inside the constructor) and the super call need to be type checked in a
     *     constructor context (outside the scope of the template). This is handled by
     *       - typedValDef, which checks if the value symbol is a constructor param or an early def
     *       - typedArg for super call arguments (SCC "self or super constructor call" mode will be enabled)
     *
     * ClassDef(C) { Template(D[T], T) {
     *   [...]

     *   <presuper> private[this] val early: Int = <empty>   // type added by `typedPrimaryConstrBody`
     *   <accessor> def early: Int = early                   // accessors generated by namer for fields
     *   [...]
     *   def <init>(param: Int): C {                 // return type added by methodSig for constructor def
     *     <presuper> val early: Int = this          // type inferred
     *     super.<init>("")                          // real super call
     *     ()
     *   }
     *   [...]
     * }}
     */
    private def primaryConstrEff(ddef: DefDef, defTyper: Typer): (Effect, List[RelEffect]) = {
      val constrSym = ddef.symbol
      val (templ, templateTyper) = templates.get(constrSym.owner)

      val typeDefAnnots = constrEffTypeDefAnnots(ddef, Some(templ), templateTyper, alreadyTyped = false)
      annotatedConstrEffect(constrSym, typeDefAnnots).getOrElse {
        
        

        val typedParents = analyzer.newTyper(templateTyper.context.outer).typedParentTypes(templ)

        val constrBody = ddef.rhs match {
          case Block(earlyVals :+ global.pendingSuperCall, unit) =>
            val argss = analyzer.superArgs(typedParents.head) getOrElse Nil
            // positions not necessarily important, anyway we don't report any effect mismatch errors here
            val pos = wrappingPos(typedParents.head.pos, argss.flatten)
            val superCall = atPos(pos)(PrimarySuperCall(argss))
            Block(earlyVals :+ superCall, unit)
          case rhs => rhs
        }

        inferPrimaryConstrEff(ddef, defTyper, templ, templateTyper,
                              typedParents, alreadyTyped = false, expected = None)
      }
    }

    /**
     * Return the typed `rhs` of a DefDef or ValDef.
     *
     * `pt` needs to be a by-name parameter so that its computation (which might trigger
     * completion of a symol) is executed within the `silent` context. Example:
     *   class C { val x = new C }
     *
     * Here, computing the type of x will
     *   - compute type of primary constructor of C, so
     *   - compute the effect of constructor C, so
     *   - compute the effect of the field initializers within C, so
     *   - call `typeCheckRhs` for rhs of `x`
     *
     * The expected type for that `typeCheckRhs` invocation is `xSymbol.tpe`, which triggers a cyclic reference
     * error. By having `pt` a by-name parameter, the cyclic reference is caught and we report a useful error
     * message ("constructor needs effect annotation").
     */
    private def typeCheckRhs(rhs: Tree, rhsTyper: Typer, pt: => Type): Tree = {
      analyzer.transformed.getOrElseUpdate(rhs, {
        try {
          rhsTyper.silent(_.typed(rhs, pt)) match {
            case SilentResultValue(t) => t
            case SilentTypeError(e) =>
              val msg = e.errMsg + "\nType error occured while type checking a tree for effect inference."
              issueTypeError(changeErrorMessage(e, msg))(rhsTyper.context)
              rhsTyper.infer.setError(rhs)
          }
        }
        catch {
          case CyclicReference(sym, info) =>
            val msg = s"Cyclic reference involving $sym.\n" +
              s"This error occured while inferring the effect of $sym, the effect needs to be annotated.\n" +
              s"If this error is reported at a field, the primary constructor needs an effect annotation."
            issueNormalTypeError(rhs, msg)(rhsTyper.context)
            rhsTyper.infer.setError(rhs)
        }
      })
    }

    def maybeAnf(tree: Tree, typer: => Typer, pt: => Type) = {
      if (requireANF) AnfTransformer.transformToAnf(tree, typer, pt)
      else tree
    }


    /**
     * This method allows modifying the type which is assigned to a definition's symbol during Namer.
     * The effect of a method is inferred if also its type is inferred.
     */
    override def pluginsTypeSig(tpe: Type, typer: Typer, defTree: Tree, pt: Type): Type = defTree match {
      case ddef @ DefDef(_, _, _, _, tpt, rhs) =>
        val sym = defTree.symbol

        def inferMethodEff(rhs: Tree = rhs, pt: Type = pt): (Effect, List[RelEffect]) = {
          // since the effect of the mehtod is inferred, the relative effect is inherited from the enclosing method.
          // see comment on `def relEffects`
          val relEnv = relEffects(sym.owner.enclMethod)
          val typedRhs = typeCheckRhs(rhs, typer, pt)
          val anfRhs = maybeAnf(typedRhs, typer, pt)

          val eff = domain.computeEffect(anfRhs, effectContext(None, relEnv, typer))
          (adaptInferredMethodEffect(sym, eff), relEnv)
        }

        def isCaseApply          = sym.isSynthetic && sym.isCase && sym.name == nme.apply
        def isCopy               = sym.isSynthetic && sym.name == nme.copy
        def isCopyDefault        = sym.isSynthetic &&
          sym.name.toString.startsWith(nme.copy.toString + nme.DEFAULT_GETTER_STRING)
        def isCaseModuleToString = sym.isSynthetic && sym.name == nme.toString_ &&
          sym.owner.isModuleClass && sym.owner.companionClass.isCaseClass

        if (sym.isPrimaryConstructor) {
          val (e, rel) = primaryConstrEff(ddef, typer)
          setEffect(tpe, e, rel)

        } else if (sym.isConstructor) {
          /* Auxiliary constructors effects are inferred just like ordinary methods. Just need to use WildcardType
           * to type check the body, not pt (which is the class type).
           */
          val typeDefAnnots = constrEffTypeDefAnnots(ddef, None, typer, alreadyTyped = false)
          val (rhsE, relEffs) = annotatedConstrEffect(sym, typeDefAnnots).getOrElse {
            
            /* All code in here is to support effect inference for auxiliary constructors.
             * The problem with those is the self constructor invocation:
             * 
             *   class C(a: Int) {
             *     def this() = this(1)
             *   }
             * 
             * In order to compute the effect of the second constructor, we first need to type
             * check its body. However, the self constructor call would fail to type check, the
             * reference to `this` triggers type completion of both constructor symbols, therefore
             * leads to a cyclic reference.
             * 
             * However, self constructor calls are special because we know they can only invoke one
             * of the earlier construtors (they cannot be recursive, or call constructor defined below).
             * 
             * The idea here is to type-check the self constructor invocation in a special context
             * which has all preceding constructors in its scope (more precisely: it has a constructor
             * symbol in scope which has an overloaded type that includes all preceding constructors).
             * 
             * So when type-checking `this(1)`, only the primary constructor is in scope, which avoids
             * the cyclic reference.
             */
            
            def isSelfConstrCall(t: Tree) = t match {
              case Ident(nme.CONSTRUCTOR) => true
              case treeInfo.Applied(Ident(nme.CONSTRUCTOR), Nil, argss) => true
              case _ => false
            }
            
            def typedSelfConstrCall(t: Tree): Tree = {
              val origCtx = typer.context
              val ctx = origCtx.makeNewScope(origCtx.tree, origCtx.owner)
              
              val (templ, _) = templates.get(sym.owner)
              val (precedingConstrs, _) = templ.body collect {
                case d @ DefDef(_, nme.CONSTRUCTOR, _, _, _, _) => d.symbol
              } span { c => c != sym }
              precedingConstrs match {
                case Nil => ()
                case s :: Nil =>
                  ctx.scope.enter(s)
                case s :: ss =>
                  val overloaded = s.owner.newOverloaded(s.info.prefix, precedingConstrs)
                  ctx.scope.enter(overloaded)
              }
              val tpr = analyzer.newTyper(ctx)
              tpr.typed(t, WildcardType)
            }
            
            val effRhs = rhs match {
              case Block(s :: ss, e) if isSelfConstrCall(s) =>
                Block(typedSelfConstrCall(s) :: ss, e).setPos(rhs.pos)

              case t if isSelfConstrCall(t) =>
                typedSelfConstrCall(t)
                
              case _ => rhs
            }
            inferMethodEff(rhs = effRhs, pt = WildcardType)
          }
          setEffect(tpe, rhsE, relEffs)

        } else if (isCaseApply || isCopy) {
          // synthetic methods: apply and copy of case class
          val (e, rel) = inferMethodEff()
          setEffect(tpe, e, rel) // TODO: correct relative effects?

        } else if (isCopyDefault || isCaseModuleToString) {
          // default getters of copy method, toString of companion object
          setEffect(tpe, bottom, Nil)

        } else if (tptWasInferred(tpt)) {
          // if the return type was inferred, also infer the effect
          val (e, rel) = inferMethodEff()
          setEffect(tpe, e, rel)

        } else {
          // for methods with annotated return types, don't change anything
          // @TODO: in case there is no effect annotation on tpe, should we add the default effect (top)?
          // things work either way, but it would be better for documentation.
          tpe
        }

      case vdef @ ValDef(_, _, tpt, rhs) if vdef.symbol.isLazy && tptWasInferred(tpt) =>
        val typedRhs = typeCheckRhs(rhs, typer, pt)
        val anfRhs = maybeAnf(typedRhs, typer, pt)

        // NOTE: if this lazy val is a field the relative environment is NOT the one of the class constructor,
        // but really the one of the enclosing method. this is correct: the lazy val is not evaluated during
        // the constructor, but whenever the field is accessed.
        val relEnv = relEffects(vdef.symbol.enclMethod)
        val e = domain.computeEffect(anfRhs, effectContext(None, relEnv, typer))
        // TODO: maybe it's a bad idea to put the effect on the lazy val type - it's the only place where a
        // value has an effect annotation, and this is exactly what we try to avoid (in pluginsTyped). Having
        // effect annotations on values leads to all random interactions with the typer, because terms suddenly
        // have effects, which triggers the annotationChecker.
        // Alternative to putting the effect on the lazy val type: put it as an attachment in the symbol. but
        // the problem is, it needs to work across separate compilation. maybe we can put it inside another
        // annotation which is not a type constraint (@lazyValEffect(...)).
        setEffect(tpe, e, relEnv)

      case impl: Template =>
        // typer.context.owner is the class symbol for ClassDefs, the moduleClassSymbol for ModuleDefs
        templates.add(typer.context.owner, impl, typer)
        tpe

      case _ =>
        tpe
    }

    /**
     * Set the effect of field accessors
     */
    override def pluginsTypeSigAccessor(tpe: Type, typer: Typer, tree: ValDef, sym: Symbol): Type = {
      val e = accessorEffect(sym, tpe, tree)
      // For the setter type, remove the effect annotation from the argument type. The reason is that the
      // ValDef's type has an effect annotation (to make constructor effect inference work), which ends
      // up in the parameter type here.
      if (sym.isSetter) {
        val MethodType(List(arg), _) = tpe
        arg.setInfo(removeAllEffectAnnotations(arg.tpe))
      }
      // for lazy vals (with inferred type), we add the rhs effect to the field type in pluginsTypeSig.
      // so the effect annotations are already there, we can just keep them
      if (sym.isLazy) tpe
      else setEffect(tpe, e, Nil)
    }


    /**
     * This method is invoked for every tree which that is type checked.
     *
     * For term trees, we remove all effect annotations from the inferred type. We don't store effects of
     * subtrees in their types.
     *
     * For Function trees, we compute the effect of the function's body and assign to the function
     * symbol a refined function type which has the inferred effect
     *
     * For all method definitions which have an annotated return type (and therefore an annotated effect)
     * we verify that the effect of the body conforms to the annotated effect. In order to do the same for
     * the primary constructor, we have to compute the effect of multiple parts of the class template.
     */
    override def pluginsTyped(tpe: Type, typer: Typer, tree: Tree, mode: Mode, pt: Type): Type =
      if (tree.isTerm) tree match {
        case Function(params, body) =>
          val funSym = tree.symbol
          val enclMeth = funSym.enclMethod
          val enclRel = domain.relEffects(enclMeth)

          lazy val bodyTyper = analyzer.newTyper(typer.context.makeNewScope(tree, funSym))
          lazy val respt: Type = {
            if (definitions.isFunctionType(pt))
              pt.normalize.typeArgs.last
            else
              WildcardType
          }
          val anfBody = maybeAnf(body, bodyTyper, respt)

          val e = domain.computeEffect(anfBody, effectContext(None, enclRel, typer))

          // we also compute the effect of the function body assuming there are no relative effects.
          // if that effect is the same (smaller or equal) as the effect e, it means that the body
          // of the function does not actually have the relative effect of the enclosing method.
          // therefore we don't assign any relative effect to the function in that case.
          //
          // this could be improved by only keeping those rel effects that actually occur in the rhs. would
          // require to inspect the rhs tree (or compute the absolute effect once for each relative effect)
          val effectiveRel =
            if (enclRel.isEmpty) Nil
            else {
              val eNoRel = domain.computeEffect(anfBody, effectContext(None, Nil, typer))
              if (eNoRel <= e) Nil else enclRel
            }

          // @TODO: not sure what the owner of the new refinement symbol should be (funSym.enclClass)?
          // @TODO: should also remove effect annotations from tpe (as in the default case below)? probably yes.
          setFunctionTypeEffect(tpe, e, effectiveRel, funSym.enclClass, tree.pos)

        case _ =>
          removeAllEffectAnnotations(tpe)

      } else {
        tree match {
          case ddef @ DefDef(_, _, _, _, tpt @ TypeTree(), rhs) =>
            val meth = tree.symbol

            val expectedEffect: Option[Effect] = {
              if (meth.isPrimaryConstructor) {
                None // primary constr effes are handled separately, see case ClassDef/ModuleDef below
              } else if (meth.isConstructor) {
                val typeDefAnnots = constrEffTypeDefAnnots(ddef, None, typer, alreadyTyped = true)
                val annots = constructorEffectAnnotations(meth, typeDefAnnots)
                // we use `annotatedConstrEffect` to test if the constructor ahs an annotated effect.
                // if that's the case, we read the expected effect from the constructor's return type.
                expectedIfNotUnchecked(annots).map(_ => fromAnnotation(meth.tpe))
              } else if (!tpt.wasEmpty) {
                val annots = meth.tpe.finalResultType.annotations
                expectedIfNotUnchecked(annots)
              } else {
                None
              }
            }

            expectedEffect foreach (annotEff => {
              lazy val rhsTyper = analyzer.newTyper(typer.context.makeNewScope(ddef, meth))
              val anfRhs = maybeAnf(rhs, rhsTyper, pt)
              val expected = Some(adaptExpectedMethodEffect(meth, annotEff))
              domain.computeEffect(anfRhs, effectContext(expected, relEffects(meth), typer))
            })

          case _: ClassDef | _: ModuleDef =>
            val templ = tree match {
              case cd: ClassDef => cd.impl
              case md: ModuleDef => md.impl
            }

            treeInfo.firstConstructor(templ.body) match {
              case constrDef: DefDef =>
                val constrSym = constrDef.symbol
                val (_, templTyper) = templates.get(constrSym.owner)
                val constrDefTyper = analyzer.newTyper(templTyper.context.makeNewScope(constrDef, constrSym))
                val typeDefAnnots = constrEffTypeDefAnnots(constrDef, Some(templ), templTyper, alreadyTyped = true)
                val annots = constructorEffectAnnotations(constrSym, typeDefAnnots)
                for (_ <- expectedIfNotUnchecked(annots)) {
                  // as expected effect we use the one on the return type of the constructor, not the one on the
                  // constructor symbol or the typeDef
                  val expected = Some(adaptExpectedMethodEffect(constrSym, fromAnnotation(constrSym.tpe)))
                  inferPrimaryConstrEff(constrDef, constrDefTyper, templ, templTyper,
                                        templ.parents, alreadyTyped = true, expected = expected)
                }

              case EmptyTree =>
                // no primary constructor, so nothing to check
            }
          case _ =>
            // nothing to check for trees other than method definitions
        }
        tpe
      }

  }

  /**
   * Returns a new effect context.
   *
   * @param expected   The expected effect. If defined and the inferred effect does not conform, computeEffect issues
   *                   an effect mismatch error.
   * @param relEnv     The relative effects of the enclosing method, used for computing the effect of an expression.
   * @param typer      The typer is only used for error reporting. It is no problem to use a typer which does not
   *                   match the context of the tree for which the effect is being inferred.
   */
  def effectContext(expected: Option[Effect], relEnv: List[RelEffect], typer: Typer, detailsMsg: Option[String] = None) = {
    def effectReporter(typer: Typer) = new EffectReporter {
      def issueError(tree: Tree, msg: String) {
        issueNormalTypeError(tree, msg)(typer.context)
      }
      def setError(tree: Tree) {
        typer.infer.setError(tree)
      }
    }

    EffectContext(expected, relEnv, effectReporter(typer), detailsMsg, patternMode = false)
  }
      
  def changeErrorMessage(err: AbsTypeError, msg: String): AbsTypeError = {
    import analyzer._
    err match {
      case AmbiguousTypeError(errPos, errMsg) => AmbiguousTypeError(errPos,msg)
      case AmbiguousImplicitTypeError(underlyingTree, errMsg) => AmbiguousImplicitTypeError(underlyingTree, msg)
      case NormalTypeError(underlyingTree, errMsg) => NormalTypeError(underlyingTree, msg)
      case AccessTypeError(underlyingTree, errMsg) => AccessTypeError(underlyingTree, msg)
      case SymbolTypeError(underlyingSym, errMsg) => SymbolTypeError(underlyingSym, msg)
      case PosAndMsgTypeError(errPos, errMsg) => PosAndMsgTypeError(errPos, msg)
      // FIXME: can't change messages for these three, problem?
      case TypeErrorWithUnderlyingTree(tree, ex) => TypeErrorWithUnderlyingTree(tree, ex)
      case DivergentImplicitTypeError(underlyingTree, pt0, sym) => DivergentImplicitTypeError(underlyingTree, pt0, sym)
      case TypeErrorWrapper(ex: TypeError) => TypeErrorWrapper(ex)
    }    
  } 
}
