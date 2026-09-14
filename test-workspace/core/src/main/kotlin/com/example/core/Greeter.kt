package com.example.core

/**
 * Renders a screen contract.
 */
interface Screen {
    fun render(): String
}

/**
 * Returns a greeting for the given [name] and [punctuation].
 */
fun greet(name: String, punctuation: String = "!"): String {
    return "Hello, $name$punctuation"
}

/**
 * Default [Screen] implementation backed by [greet].
 */
class Greeter(val target: String) : Screen {
    override fun render(): String = greet(target)
}
