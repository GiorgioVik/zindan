package net.typeblog.shelter.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcel
import android.os.Parcelable

class ApplicationInfoWrapper private constructor() : Parcelable {
    private var info: ApplicationInfo? = null
    private var label: String? = null
    private var isHidden: Boolean = false

    constructor(info: ApplicationInfo) : this() {
        this.info = info
    }

    fun loadLabel(pm: PackageManager): ApplicationInfoWrapper {
        label = pm.getApplicationLabel(info!!).toString()
        return this
    }

    // Only used from ShelterService
    fun setHidden(hidden: Boolean): ApplicationInfoWrapper {
        isHidden = hidden
        return this
    }

    fun getPackageName(): String = info!!.packageName

    fun getLabel(): String? = label

    fun getSourceDir(): String = info!!.sourceDir

    fun getSplitApks(): Array<String>? = info!!.splitSourceDirs

    // NOTE: This does not relate to the "freezing" feature in Shelter
    fun getEnabled(): Boolean = info!!.enabled

    fun isHidden(): Boolean = isHidden

    fun getInfo(): ApplicationInfo? = info

    fun isSystem(): Boolean = (info!!.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(info, flags)
        dest.writeString(label)
        dest.writeByte((if (isHidden) 1 else 0).toByte())
    }

    override fun describeContents(): Int = info!!.packageName.hashCode()

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ApplicationInfoWrapper> =
            object : Parcelable.Creator<ApplicationInfoWrapper> {
                override fun createFromParcel(source: Parcel): ApplicationInfoWrapper {
                    val wrapper = ApplicationInfoWrapper()
                    wrapper.info = source.readParcelable(
                        ApplicationInfo::class.java.classLoader
                    )
                    wrapper.label = source.readString()
                    wrapper.isHidden = source.readByte().toInt() != 0
                    return wrapper
                }

                override fun newArray(size: Int): Array<ApplicationInfoWrapper?> =
                    arrayOfNulls(size)
            }
    }
}
