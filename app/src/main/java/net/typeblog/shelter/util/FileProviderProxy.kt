package net.typeblog.shelter.util

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.FileNotFoundException

// A simple and naïve FileProvider which forwards content Uris
// to a given Uri from another profile through UriForwardProxy
// This class can, for now, only forward one Uri each time.
// It forwards all requests to one Uri assigned through
// static methods.
class FileProviderProxy : FileProvider() {
    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.path!!.startsWith(FORWARD_PATH_PREFIX) && proxy != null) {
            return proxy!!.open(mode)
                ?: throw FileNotFoundException("Forwarded Uri could not be opened")
        }
        return super.openFile(uri, mode)!!
    }

    companion object {
        private const val AUTHORITY_NAME = "net.typeblog.shelter.files"
        private const val FORWARD_PATH_PREFIX = "/forward/"
        private var proxy: UriForwardProxy? = null

        fun setUriForwardProxy(proxy: UriForwardProxy, suffix: String): Uri {
            Companion.proxy = proxy
            return Uri.parse("content://$AUTHORITY_NAME$FORWARD_PATH_PREFIX" + "temp.$suffix")
        }

        fun clearForwardProxy() {
            proxy = null
        }
    }
}
