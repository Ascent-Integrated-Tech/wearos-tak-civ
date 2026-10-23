package com.aitech.weartak_civ.presentation

import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.command.ProfilePresetFiles
import com.aitech.weartak_civ.command.ProfilePresetPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

@Composable
fun CommandProfilesPage(navController: NavController, name: String? = null) {
    val context = LocalContext.current
    val state by produceState<Result<Pair<List<String>, ProfilePresetPreview?>>?>(null, name) {
        value = withContext(Dispatchers.IO) {
            try {
                val base = context.getExternalFilesDir(null)
                    ?: throw IOException("Profile storage is unavailable")
                val directory = File(base, "presets")
                Result.success(if (name == null) ProfilePresetFiles.list(directory) to null
                    else emptyList<String>() to ProfilePresetFiles.preview(directory, name))
            } catch (error: IOException) {
                Log.w("WearTAK-CommandProfiles", "Unable to read saved profiles")
                Result.failure(error)
            } catch (error: SecurityException) {
                Log.w("WearTAK-CommandProfiles", "Saved profile storage access denied")
                Result.failure(error)
            } catch (error: IllegalArgumentException) {
                Log.w("WearTAK-CommandProfiles", "Invalid saved profile selection")
                Result.failure(error)
            }
        }
    }
    CommandProfilesContent(navController, name, state)
}

@Composable
internal fun CommandProfilesContent(
    navController: NavController,
    name: String?,
    state: Result<Pair<List<String>, ProfilePresetPreview?>>?
) {
    WearTAKPageWithBackArrow(
        title = name ?: stringResource(R.string.command_profiles),
        navController = navController,
        columnContent = {
            when {
                state == null -> item { ProfileText(stringResource(R.string.command_profiles_loading)) }
                state.isFailure -> item { ProfileText(stringResource(R.string.command_profiles_error)) }
                name == null -> {
                    val names = state.getOrThrow().first
                    item { ProfileText(stringResource(R.string.command_profiles_read_only)) }
                    if (names.isEmpty()) item { ProfileText(stringResource(R.string.command_profiles_empty)) }
                    names.forEach { preset ->
                        item {
                            WearTAKTitleChip(
                                title = preset, route = "chat_command_profile/$preset", navController = navController
                            )
                        }
                    }
                }
                else -> {
                    val preview = state.getOrThrow().second
                    item { ProfileText(stringResource(R.string.command_profiles_read_only)) }
                    if (preview?.fields.isNullOrEmpty()) {
                        item { ProfileText(stringResource(R.string.command_profiles_no_fields)) }
                    }
                    preview?.fields?.forEach { (key, value) ->
                        item {
                            val label = when (key) {
                                "callsign" -> R.string.callsign
                                "team" -> R.string.team
                                "role" -> R.string.role
                                "height" -> R.string.command_profile_height
                                "weight" -> R.string.command_profile_weight
                                "pants" -> R.string.command_profile_pants
                                "waist" -> R.string.command_profile_waist
                                "birthyear" -> R.string.command_profile_birthyear
                                "sex" -> R.string.command_profile_sex
                                "usertype" -> R.string.command_profile_usertype
                                else -> null
                            }
                            ProfileText("${label?.let { stringResource(it) } ?: key}: $value")
                        }
                    }
                    item { Text(stringResource(R.string.command_profiles_servers), style = MaterialTheme.typography.title3) }
                    if (preview?.servers.isNullOrEmpty()) {
                        item { ProfileText(stringResource(R.string.command_profiles_no_servers)) }
                    }
                    preview?.servers?.forEach { server ->
                        item { ProfileText("${server.name}\n${server.host}:${server.port}") }
                    }
                }
            }
        }
    )
}

@Composable
private fun ProfileText(text: String) {
    Text(
        text, style = MaterialTheme.typography.body2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        softWrap = true
    )
}
