package com.xxsx.earthlore.core;

/** Helpers for "namespace:code/slug" node ids. */
public final class NodeIds {
    private NodeIds() {
    }

    public static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    public static String path(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }
}

