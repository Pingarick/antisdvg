package com.antisdvg.ui.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.antisdvg.R
import com.antisdvg.databinding.ActivityMainBinding

/** App shell hosting the bottom-tab navigation (library, stats, settings). */
class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host) as NavHostFragment
        binding.bottomNav.setupWithNavController(navHost.navController)

        handleNavigationIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra("open_reading", false) == true) {
            val navHost = supportFragmentManager
                .findFragmentById(R.id.nav_host) as? NavHostFragment
            navHost?.navController?.navigate(R.id.nav_reading)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }
}
