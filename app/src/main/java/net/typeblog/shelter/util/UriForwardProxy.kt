package net.typeblog.shelter.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import android.os.RemoteException
import java.io.FileNotFoundException

// A wrapper over Uri to remotely open an Uri through AIDL
// This is used to forward Content URIs through the profile
// boundary.
class UriForwardProxy private constructor(private val opener: IUriOpener) : Parcelable {
    constructor(context: Context, uri: Uri) : this(
        object : IUriOpener.Stub() {
            override fun openFile(mode: String): ParcelFileDescriptor? {
                return try {
                    context.contentResolver.openFileDescriptor(uri, mode)
                } catch (e: FileNotFoundException) {
                    null
                }
            }
        }
    )

    fun open(mode: String): ParcelFileDescriptor? {
        return try {
            opener.openFile(mode)
        } catch (e: RemoteException) {
            null
        }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeBinderArray(arrayOf(opener.asBinder()))
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<UriForwardProxy> =
            object : Parcelable.Creator<UriForwardProxy> {
                override fun createFromParcel(source: Parcel): UriForwardProxy {
                    val arr = arrayOfNulls<IBinder>(1)
                    source.readBinderArray(arr)
                    val opener = IUriOpener.Stub.asInterface(arr[0])
                    return UriForwardProxy(opener)
                }

                override fun newArray(size: Int): Array<UriForwardProxy?> =
                    arrayOfNulls(0)
            }
    }
}
