package com.example.demo

/**
 * Returns a greeting for the given name.
 */
fun greet(name: String): String {
    return "Hello, $name!"
}

class Greeter {
    fun sayHello(): String = greet("World")
}

fun main() {
    val greeter = Greeter()
    println(greeter.sayHello())
}