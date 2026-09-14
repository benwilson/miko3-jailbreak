# Concepts

> Shared domain vocabulary for this project — entities, named processes, and status concepts with project-specific meaning. Seeded with core domain vocabulary, then accretes as ce-compound and ce-compound-refresh process learnings; direct edits are fine. Glossary only, not a spec or catch-all.

## Boot ladder

The sequence of USB enumeration states a Miko 3 passes through after a power cycle: BROM, the MediaTek preloader stage, the fastboot gadget once a mode name has been written, the normal Android gadget, and the accessory state reached from an AOA handshake. It is the schedule every host-side route is timed against: each stage has its own window, its own interface descriptor, and its own usable or unusable character, and the ladder recurs within one power cycle rather than being a one-shot event.

## Preloader window

The short, repeating interval during a boot in which the MediaTek preloader stage holds the USB connection and answers on its own serial node. It is the only stage that gives a host a bidirectional console without the kiosk app's cooperation, and it is short enough that the order of host-side attempts matters more than their content. A missed occurrence is retried within the same power cycle, since the stage comes back several times before the unit settles.

## META mode

The MediaTek text handshake the preloader window speaks: the host sends the download-mode sync, the device answers that it is ready, and the host then names the stage to switch to. The mode name is what makes the handshake useful — the device moves to the named stage rather than merely acknowledging. Named stages are the ones the firmware recognises, not arbitrary labels.

## Accessory mode

The USB state a unit enters once an Android Open Accessories handshake succeeds, in which the host's descriptor and HID registrations drive input to the device. It is a distinct enumeration state from the normal Android gadget state, so its appearance confirms the handshake and says nothing about whether adb is exposed.

## Interface triplet

The class, subclass, and protocol values read from a device's USB interface descriptor, used here as the machine-readable check of what a given boot stage actually offers. It is preferred over parsing adb's own device listing because it needs no RSA authorization and answers even when that listing is empty. Three states carry meaning: a vendor-only triplet means no adb interface is present, a triplet with the adb subclass and protocol means adb is reachable, and the fastboot gadget's triplet means fastboot answers while adb still does not.

## Kiosk

The unit's single-purpose home screen, reached only through a normal boot. It is the state the unit is meant to settle into, and the state every recovery effort is trying to restore; a host-side console is a detour around it, not a substitute for it. The kiosk is composed of paired system apps, so "the kiosk boots" means both halves reached their expected versions, not merely that a home activity launched.

## Paired system apps

Two preloaded apps that share a binder contract and therefore must be restored together at matching versions: one exposes a bound service, the other binds to it and drives the visible screen. An OTA ships them as a set, and applying only one leaves the contract skewed. The pairing is not expressed anywhere in the code — it is discovered from the failure it causes when broken.

## Service init handshake

The binder exchange a kiosk performs at startup before it will show anything, in which the screen-driving app binds to the service-exposing app and calls an initialization method across that binder. It is a gate, not a notification: until it completes, the kiosk stays on its startup screen, and a version skew between the pair makes it fail silently rather than report an error.

## Bot restart screen

The looping startup animation a kiosk shows while it waits for the service init handshake to complete. It repeats deliberately and indefinitely, so it is indistinguishable from a hang to a viewer; it is dismissed only by the handshake succeeding, never by a timeout. It is the visible symptom of any failure in that handshake.

## Watchdog

The kiosk-side watchdog that unconditionally disables adb access on every normal boot, and separately, on an ongoing timer, reboots the unit if it observes the adb daemon running anyway — two independent hostile actions, not one, so defeating the reboot alone does not keep adb reachable.

The adb-disable runs once, early, before the reboot check starts polling, which is why a countermeasure that only defeats the reboot arrives too late to matter unless it also blocks the disable itself.

## Boot agent

The app that runs the root payload on every normal boot: a manifest `BOOT_COMPLETED` receiver that execs the setuid `su` and then brings adb up. It is the only way to run a root command at boot on this unit, because verity keeps the system and vendor init read-only and no init trigger consumes an adb property. Being installed means the receiver is registered and past the stopped state, not merely that the APK is present.

## Neuterd

The small freestanding arm64 daemon, reused from the openmiko research, that keeps the watchdog's hostile actions neutered by shadowing the system commands it uses to perform them. It enters init's global mount namespace once, then continuously re-applies each shadow whenever the command it targets reverts to its real, unshadowed form, so the neuter survives whatever wipes it. It exists because the mount has to be made in the namespace the watchdog actually sees, not an app's private one.

A shadow is not always a blanket no-op: it can instead filter, letting a targeted command through unchanged except for the one specific call it exists to block, which matters when the same command is also needed for legitimate purposes.

## Global mount namespace

The namespace init owns, as opposed to the private one an app process gets. A bind mount made from an app lands in the app's namespace and is invisible to the watchdog, a zygote child in the global one, so a neuter is only real when it is made after entering init's namespace. It is why the watchdog fix needs a native daemon rather than an app-side mount.

## Stopped state

Android's per-package flag, recorded in the per-user package-restrictions file, that keeps a freshly installed app from receiving broadcasts until it has been launched once. It is what makes a boot receiver silent, and the reason installation cannot stop at placing the APK: this unit has no activity manager in factory mode, so the flag is cleared directly rather than by launching the app.
