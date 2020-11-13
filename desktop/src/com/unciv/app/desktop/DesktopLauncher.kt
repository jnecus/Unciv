package com.unciv.app.desktop

import club.minnced.discord.rpc.DiscordEventHandlers
import club.minnced.discord.rpc.DiscordRPC
import club.minnced.discord.rpc.DiscordRichPresence
import com.badlogic.gdx.Files
import com.badlogic.gdx.backends.lwjgl.LwjglApplication
import com.badlogic.gdx.backends.lwjgl.LwjglApplicationConfiguration
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.tools.texturepacker.TexturePacker
import com.unciv.UncivGame
import com.unciv.UncivGameParameters
import com.unciv.models.translations.tr
import com.unciv.ui.utils.ORIGINAL_FONT_SIZE
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.request.receiveText
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File
import java.util.*
import kotlin.concurrent.timer

internal object DesktopLauncher {
    private var discordTimer: Timer? = null

    @JvmStatic
    fun main(arg: Array<String>) {
        // Solves a rendering problem in specific GPUs and drivers.
        // For more info see https://github.com/yairm210/Unciv/pull/3202 and https://github.com/LWJGL/lwjgl/issues/119
        System.setProperty("org.lwjgl.opengl.Display.allowSoftwareOpenGL", "true")

        packImages()

        val config = LwjglApplicationConfiguration()
        // Don't activate GL 3.0 because it causes problems for MacOS computers
        config.addIcon("ExtraImages/Icon.png", Files.FileType.Internal)
        config.title = "Unciv"
        config.useHDPI = true

        val versionFromJar = DesktopLauncher.javaClass.`package`.specificationVersion ?: "Desktop"

        val desktopParameters = UncivGameParameters(
                versionFromJar,
                cancelDiscordEvent = { discordTimer?.cancel() },
                fontImplementation = NativeFontDesktop(ORIGINAL_FONT_SIZE.toInt()),
                customSaveLocationHelper = CustomSaveLocationHelperDesktop()
        )

        val game = UncivGame ( desktopParameters )

        if(!RaspberryPiDetector.isRaspberryPi()) // No discord RPC for Raspberry Pi, see https://github.com/yairm210/Unciv/issues/1624
            tryActivateDiscord(game)

        LwjglApplication(game, config)
    }

    private fun startMultiplayerServer() {
//        val games = HashMap<String, GameSetupInfo>()
        val files = HashMap<String, String>()
        embeddedServer(Netty, 8080) {
            routing {
                get("/files/getFile/{fileName}") {
                    val fileName = call.parameters["fileName"]
                    if (!files.containsKey(fileName)) call.respond(HttpStatusCode.NotFound,
                            "Game with the name $fileName does not exist")
                    else call.respondText(files[fileName]!!)
                }

                post("/files/{fileName}") {
                    val fileName = call.parameters["fileName"]!!
                    val body = call.receiveText()
                    files[fileName] = body
                }
//
//                get("/getGame/{gameName}") {
//                    val gameName = call.parameters["gameName"]
//                    if(!games.containsKey(gameName)) call.respond(HttpStatusCode.NotFound,
//                            "Game with the name $gameName does not exist")
//                    else call.respondText(Json().toJson(games[gameName]))
//                }
//                get("/getGameNames"){
//                    call.respondText(Json().toJson(games.keys.toList()))
//                }
//                post("/addNewGame/{gameName}") {
//                    val gameName = call.parameters["gameName"]!!
//                    if (games.containsKey(gameName)) {
//                        call.respond(HttpStatusCode.NotAcceptable, "A game with the name $gameName already exists")
//                        return@post
//                    }
//                    val body = call.receiveText()
//                    val newGameInfo:GameSetupInfo
//                    try{
//                        newGameInfo = Json().apply { ignoreUnknownFields }.fromJson(GameSetupInfo::class.java, body)
//                    }
//                    catch (ex:Exception){
//                        call.respond(HttpStatusCode.NotAcceptable, "Could not deserialize json")
//                        return@post
//                    }
//                    games[gameName] = newGameInfo
//                }
            }
        }.start()
    }


    private fun packImages() {
        val startTime = System.currentTimeMillis()

        val settings = TexturePacker.Settings()
        // Apparently some chipsets, like NVIDIA Tegra 3 graphics chipset (used in Asus TF700T tablet),
        // don't support non-power-of-two texture sizes - kudos @yuroller!
        // https://github.com/yairm210/UnCiv/issues/1340

        /**
         * These should be as big as possible in order to accommodate ALL the images together in one bug file.
         * Why? Because the rendering function of the main screen renders all the images consecutively, and every time it needs to switch between textures,
         * this causes a delay, leading to horrible lag if there are enough switches.
         * The cost of this specific solution is that the entire game.png needs be be kept in-memory constantly.
         * It's currently only 1.5MB so it should be okay, but it' an important point to remember for the future.
         * Sidenote: Modded tilesets don't have this problem, since all the images are included in the mod's single PNG.
         * Probably. Unless they have some truly huge images.
         * Sidenote 2: Okay entire so custom tilesets do have this problem because they can get so big that what accommodates
         * the regular tileset (4096) doesn't suit them. Looking at you 5hex.
         */
        settings.maxWidth = 4096
        settings.maxHeight = 4096

        settings.combineSubdirectories = true
        settings.pot = true
        settings.fast = true

        // This is so they don't look all pixelated
        settings.filterMag = Texture.TextureFilter.MipMapLinearLinear
        settings.filterMin = Texture.TextureFilter.MipMapLinearLinear

        if (File("../Images").exists()) // So we don't run this from within a fat JAR
            packImagesIfOutdated(settings, "../Images", ".", "game")

        // pack for mods as well
        val modDirectory = File("mods")
        if(modDirectory.exists()) {
            for (mod in modDirectory.listFiles()!!){
                if (!mod.isHidden && File(mod.path + "/Images").exists())
                    packImagesIfOutdated(settings, mod.path + "/Images", mod.path, "game")
            }
        }

        val texturePackingTime = System.currentTimeMillis() - startTime
        println("Packing textures - "+texturePackingTime+"ms")
    }

    private fun packImagesIfOutdated (settings: TexturePacker.Settings, input: String, output: String, packFileName: String) {
        fun File.listTree(): Sequence<File> = when {
                this.isFile -> sequenceOf(this)
                this.isDirectory -> this.listFiles().asSequence().flatMap { it.listTree() }
                else -> sequenceOf()
            }

        val atlasFile = File("$output${File.separator}$packFileName.atlas")
        if (atlasFile.exists() && File("$output${File.separator}$packFileName.png").exists()) {
            val atlasModTime = atlasFile.lastModified()
            if (!File(input).listTree().any { it.extension in listOf("png","jpg","jpeg") && it.lastModified() > atlasModTime }) return
        }
        TexturePacker.process(settings, input, output, packFileName )
    }

    private fun tryActivateDiscord(game: UncivGame) {
        try {
            val handlers = DiscordEventHandlers()
            DiscordRPC.INSTANCE.Discord_Initialize("647066573147996161", handlers, true, null)

            Runtime.getRuntime().addShutdownHook(Thread { DiscordRPC.INSTANCE.Discord_Shutdown() })

            discordTimer = timer(name = "Discord", daemon = true, period = 1000) {
                try {
                    updateRpc(game)
                } catch (ex:Exception){}
            }
        } catch (ex: Exception) {
            println("Could not initialize Discord")
        }
    }

    private fun updateRpc(game: UncivGame) {
        if(!game.isInitialized) return
        val presence = DiscordRichPresence()
        val currentPlayerCiv = game.gameInfo.getCurrentPlayerCivilization()
        presence.details=currentPlayerCiv.nation.getLeaderDisplayName().tr()
        presence.largeImageKey = "logo" // The actual image is uploaded to the discord app / applications webpage
        presence.largeImageText ="Turn".tr()+" " + currentPlayerCiv.gameInfo.turns
        DiscordRPC.INSTANCE.Discord_UpdatePresence(presence)
    }
}
