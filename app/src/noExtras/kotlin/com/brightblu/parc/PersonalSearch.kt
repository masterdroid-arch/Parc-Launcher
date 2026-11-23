package com.brightblu.parc

import androidx.compose.runtime.Composable
import com.brightblu.parc.feature.search.AppsSearchExtension
import com.brightblu.parc.model.*

@Composable
internal fun personalSearchExtension(apps: List<AppEntry>, folders: List<Folder>, open: (FolderId) -> Unit): AppsSearchExtension? = null

@Composable
internal fun PersonalSearchSwitch(skin: Skin) {}
