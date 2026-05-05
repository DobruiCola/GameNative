package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Hogwarts Legacy (Epic)
 *
 * Wipes C:\ProgramData\Hogwarts Legacy on every boot. The game caches state there
 * that occasionally leaves Denuvo / EOS in a bad mood after a previous session.
 */
val EPIC_Fix_fa4240e57a3c46b39f169041b7811293: KeyedGameFix = KeyedDeleteFolderFix(
    gameSource = GameSource.EPIC,
    gameId = "fa4240e57a3c46b39f169041b7811293",
    driveCRelativePaths = listOf("ProgramData/Hogwarts Legacy"),
)
