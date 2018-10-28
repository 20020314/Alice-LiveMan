/*
 * <Alice LiveMan>
 * Copyright (C) <2018>  <NekoSunflower>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package site.alice.liveman.event;

import site.alice.liveman.mediaproxy.proxytask.MediaProxyTask;

import java.util.EventObject;

public class MediaProxyEvent extends EventObject {
    private MediaProxyTask mediaProxyTask;

    /**
     * Constructs a prototypical Event.
     *
     * @param source The object on which the Event initially occurred.
     * @throws IllegalArgumentException if source is null.
     */
    public MediaProxyEvent(Object source) {
        super(source);
    }

    public MediaProxyTask getMediaProxyTask() {
        return mediaProxyTask;
    }

    public void setMediaProxyTask(MediaProxyTask mediaProxyTask) {
        this.mediaProxyTask = mediaProxyTask;
    }
}
