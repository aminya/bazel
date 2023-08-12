package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import net.starlark.java.syntax.Location;

import java.util.List;

public final class ModuleFileFixupEvent implements ExtendedEventHandler.Postable {
  private final ImmutableList<String> buildozerCommands;
  private final Location location;
  private final String warning;
  private final ModuleExtensionUsage usage;

  public ModuleFileFixupEvent(
      Location location,
      String warning,
      List<String> buildozerCommands,
      ModuleExtensionUsage usage) {
    this.buildozerCommands = ImmutableList.copyOf(buildozerCommands);
    this.location = location;
    this.warning = warning;
    this.usage = usage;
  }

  public ImmutableList<String> getBuildozerCommands() {
    return buildozerCommands;
  }

  public String getSuccessMessage() {
    String extensionId = usage.getExtensionBzlFile() + "%" + usage.getExtensionName();
    return usage
        .getIsolationKey()
        .map(
            key ->
                String.format(
                    "Updated use_repo calls for isolated usage '%s' of %s",
                    key.getUsageExportedName(), extensionId))
        .orElseGet(() -> String.format("Updated use_repo calls for %s", extensionId));
  }

  @Override
  public void reportTo(ExtendedEventHandler handler) {
    handler.post(this);
    handler.handle(Event.warn(location, warning));
  }

  @Override
  public boolean storeForReplay() {
    return true;
  }
}
