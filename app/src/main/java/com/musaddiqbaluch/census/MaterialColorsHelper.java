package com.musaddiqbaluch.census;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;

public final class MaterialColorsHelper {

    private MaterialColorsHelper() {
    }

    // Resolve a Material theme color into an actual ARGB integer.
    public static int getColor(Context context, int attr) {
        TypedValue value = new TypedValue();

        if (!context.getTheme().resolveAttribute(attr, value, true)) {
            throw new IllegalArgumentException(
                "Theme attribute is not defined: " + attr
            );
        }

        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }

        if (value.resourceId != 0) {
            return context.getColor(value.resourceId);
        }

        throw new IllegalArgumentException(
            "Attribute is not a color resource: " + attr
        );
    }

    // Resolve a theme attribute as a ColorStateList.
    public static ColorStateList getColorStateList(
            Context context, int attr) {

        TypedValue value = new TypedValue();

        if (!context.getTheme().resolveAttribute(attr, value, true)) {
            throw new IllegalArgumentException(
                "Theme attribute is not defined: " + attr
            );
        }

        if (value.resourceId != 0) {
            return context.getColorStateList(value.resourceId);
        }

        return ColorStateList.valueOf(value.data);
    }
}
