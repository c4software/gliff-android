package io.github.c4software.gliff.di

import io.github.c4software.gliff.data.Logger
import io.github.c4software.gliff.data.clipboard.ClipboardWriter
import io.github.c4software.gliff.data.clipboard.IosClipboard
import io.github.c4software.gliff.data.local.KeyValueStore
import io.github.c4software.gliff.data.local.UserDefaultsStore
import io.github.c4software.gliff.data.security.IosIdentity
import io.github.c4software.gliff.data.session.Connector
import io.github.c4software.gliff.data.session.IosConnector
import io.github.c4software.gliff.data.session.SshBridge
import io.github.c4software.gliff.data.video.VideoDecoderFactory
import io.github.c4software.gliff.data.video.VideoToolboxDecoder
import io.github.c4software.gliff.domain.identity.Identity
import org.koin.dsl.bind
import org.koin.dsl.module
import platform.Foundation.NSLog

/** What iOS provides to the graph, see [appModules]. There is no web mirror. */
fun iosModule(bridge: SshBridge) = module {
    single<KeyValueStore> { UserDefaultsStore() }
    single<Identity> { IosIdentity(bridge) }
    single { IosClipboard() } bind ClipboardWriter::class
    single<Logger> { Logger { message, cause -> NSLog("gliff: %@", if (cause == null) message else "$message: $cause") } }
    single<Connector> { IosConnector(bridge) { NSLog("gliff-server: %@", it) } }
    single { VideoDecoderFactory(::VideoToolboxDecoder) }
}
