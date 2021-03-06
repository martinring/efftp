import annotation.effects._

class C {
  class A1
  def f1(): A1 @loc() = new A1
  
  class A2 {
    def this(x: Int) = this()
  }
  def f2a: A2 @loc() = new A2()
  def f2b: A2 @loc() = new A2(101)
  
  class A3 {
    var x: Int = 0
    def this(y: Int) {
      this()
      x = y
    }
  }
  def f3a: A3 @loc() = new A3()
  def f3b: A3 @loc() = new A3(102)


  class A4 {
    @loc() type constructorEffect
  }
  
  @loc()
  class A5

  
  class A6 {
    @loc() def this(x: Int) = this()
  }
  
  class A7 {
    @mod(this) @loc() type constructorEffect
    var x = 0
    x = 1
  }

  class A8 {
    @mod(this) @loc() type constructorEffect
    var x = 0
  }


  class A9 {
    var x = 0
    @mod(this) @loc() def this(x: Int) = {
      this()
      this.x = x
    }
  }

  class A10(a: Int) {
    @mod(this) @loc() type constructorEffect
    var b = a
    @mod(this) @loc() def this(x: Int, y: Int) {
      this(0)
      b = x + y
    }
  }
  def f10: A10 @loc() = new A10(10, 20)


  
  trait D

  // mod(any) because parameter `a: D` is not annotated `@local`
  @mod(any) @loc()
  class A11(a: D) {
    @local var d: D = a
  }
  def f11(d: D): A11 @mod(any) @loc() = new A11(d)


  class A12(@local a: D) {
    @local var d: D = a
  }
  def f12(d: D): A12 @mod(d) @loc() = new A12(d)

  
  class K

  class A13 {
    @local var c = new K
    def this(k: K) {
      this()
      c = k
    }
  }
  def f13a(): A13 @loc() = new A13
  def f13b(): A13 @loc() = new A13(new K)
  def f13c(k: K): A13 @mod(k) @loc() = new A13(k)

  class A13Trait {
    @local var c = new D {}
    def this(k: D) {
      this()
      c = k
    }
  }
  def f13Ta(): A13Trait @loc() = new A13Trait
  def f13Tb(): A13Trait @loc() = new A13Trait(new D {})
  def f13Tc(d: D): A13Trait @mod(d) @loc() = new A13Trait(d)
  
}
