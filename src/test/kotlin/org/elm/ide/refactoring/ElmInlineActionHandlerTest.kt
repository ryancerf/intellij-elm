package org.elm.ide.refactoring

import org.elm.lang.ElmTestBase
import org.intellij.lang.annotations.Language


class ElmInlineActionHandlerTest : ElmTestBase() {


    fun `test replace the let entirely, very simple usage expr`() = doTest(
            """
module Main exposing (..)
f =
    let
        y = 0
    in
    y{-caret-}
""", """
module Main exposing (..)
f =
    0
""")


    fun `test replace the let entirely, single-line usage expr`() = doTest(
            """
module Main exposing (..)
f x =
    let
        y = 0
    in
    x + y{-caret-}
""", """
module Main exposing (..)
f x =
    x + 0
""")


    fun `test replace the let entirely, multi-line usage expr`() = doTest(
            """
module Main exposing (..)
f g h =
    let
        y = 0
    in
    g y
        |> h
""", """
module Main exposing (..)
f g h =
    g 0
        |> h
""")


    // TODO [kl] Ideally we wouldn't generate unnecessary parens, but for the time being this is fine.
    //           The user can invoke `elm-format` to clean things up.
    fun `test replace the let entirely, inlining a value that can be wrapped in parens`() = doTest(
            """
module Main exposing (..)
f g =
    let
        y = g 0
    in
    y{-caret-}
""", """
module Main exposing (..)
f g =
    (g 0)
""")


    fun `test replace the let entirely, inlining a multi-line value`() = doTest(
            """
module Main exposing (..)
f g =
    let
        y =
            [ 0
            , 1
            , 2
            ]
    in
    g y{-caret-}
""", """
module Main exposing (..)
f g =
    g [ 0
      , 1
      , 2
      ]
""")


    fun `test replace the let entirely, inlining a multi-line value that needs parens`() = doTest(
            """
module Main exposing (..)
f g h =
    let
        y =
            h
                0
                1
    in
    g y{-caret-}
""", """
module Main exposing (..)
f g h =
    g
        (h
            0
            1
        )
""")


    fun `test keep the let, deleting the first inner decl`() = doTest(
            """
module Main exposing (..)
f =
    let
        y = 0
        z = 1
    in
    y{-caret-} + z
""", """
module Main exposing (..)
f =
    let
        z = 1
    in
    0 + z
""")


    fun `test keep the let, deleting the last inner decl`() = doTest(
            """
module Main exposing (..)
f =
    let
        y = 0
        z = 1
    in
    y + z{-caret-}
""", """
module Main exposing (..)
f =
    let
        y = 0
    in
    y + 1
""")


    fun `test function parameters cannot be inlined`() = doUnavailableTest(
            """
module Main exposing (..)
f x =
    x{-caret-}
""")


    fun `test only let decls can be inlined as of now`() = doUnavailableTest(
            """
module Main exposing (..)
x = 0
f = x{-caret-}
""")


    private fun doTest(@Language("Elm") code: String, @Language("Elm") excepted: String) =
            checkByText(code, excepted) {
                myFixture.performEditorAction("Inline")
            }

    private fun doUnavailableTest(@Language("Elm") code: String) =
            doTest(code, code)
}