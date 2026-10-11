package com.nila.skin.ui

import androidx.core.content.FileProvider

/**
 * Its own class, so it can sit beside a host app's FileProvider: the manifest merger
 * matches providers by class name, and two `androidx.core.content.FileProvider`
 * entries with different authorities fail the merge (as they do inside Nila).
 */
class SkinFileProvider : FileProvider()
