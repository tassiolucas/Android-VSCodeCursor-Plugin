package com.example.app

import com.example.core.Greeter
import com.example.core.Screen
import com.example.core.greet

class App : Screen {
    private val greeter = Greeter("Android")
    private val screen: Screen = this

    override fun render(): String {
        val title = R.string.app_name
        return greeter.render() + greet("App")
    }
}

fun main() {
    println(App().render())
}
