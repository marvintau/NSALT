# Refactoring Note
Yue Marvin Tao, Institute of Software, CAS

## 1. Principle of Code Structuring

Break large function unit into small components, preferably the
Minimum Verifiable Component (MVC)

Typically, the MVC **IS** a
- Finite state machine
- Memory-like component (RAM / Register Bank / etc.) 
- complex sequential logic

and **IS NOT** a
- componenent including multiple independent FSMs
- memories with independent IOs
- seqeuntial logics sharing same timing dependency
- combination of above.

Small components reduce design complexity, and scales down the state
space when testing.

## 2. Styling Conventions

1. `BoringUtils` should be eliminated since it makes it difficult to
   test.

2. Naming:

  - Abbreviation:
  
    avoid using acronyms (e.g. FU / PPN / BTB / etc.). They are hard to
    understand without familiarity of the context, even they could be
    conventional in EE or computer architecture area. Use abbreviation 
    instead (e.g. FuncUnit / PhysPageNum / etc.)

  - Distinguish the identifier type:

    * constants: in-file constant variables or outermost properties of
      configuration. Use UPPER_SNAKE_CASE, (e.g. DATA_BITS / ADDR_BITS /
      etc.)

    * variables: variables declared with `var`, including port spec
      should follow lowerCamelCase, (e.g. io.in.isMispredicted)

    * class / trait / object: all structure regarding "constructor"
      should follow UpperCamelCase

  - Affix:

    Specify the use, instead of data type. For instance, `Bundle` in
    Chisel 3 can be used as port, or data structure. Thus a structure
    declared as `Bundle` should reflect whether it is port or data
    in its name.

3. Utilize name space

   proper name space can effectively reduce the length of naming. For
   example, all names regarding `SimpleBus` can be put under the name
   space of `SimpleBus`.


## 3. Refactoring tasks

1. Split code w.r.t. different cores into separate files.

2. Remove all `sealed` keywords for further code splitting.

3. Change `BoringUtils` into normal port definition with `IO` and `Bundle`