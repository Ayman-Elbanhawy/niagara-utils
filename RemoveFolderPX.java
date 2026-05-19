private static final String ROOT_ORD = "station:|slot:/";
private static final boolean DRY_RUN = true;

private int folderCount;
private int pxViewCount;
private int removedCount;
private int errorCount;

public void onExecute() throws Exception {
    folderCount = 0;
    pxViewCount = 0;
    removedCount = 0;
    errorCount = 0;

    BComponent root = (BComponent) BOrd.make(ROOT_ORD).resolve().get();

    System.out.println("Start scanning from: " + root.getSlotPathOrd());
    System.out.println("Dry run: " + DRY_RUN);

    scan(root);

    System.out.println("Done.");
    System.out.println("Folders scanned: " + folderCount);
    System.out.println("PxView slots found: " + pxViewCount);
    System.out.println("PxView slots removed: " + removedCount);
    System.out.println("Errors: " + errorCount);
}

private void scan(BComponent component) {
    try {
        if (component instanceof BFolder) {
            folderCount++;
            removePxViews((BFolder) component);
        }

        BComponent[] children = component.getChildComponents();
        for (int i = 0; i < children.length; i++) {
            scan(children[i]);
        }
    } catch (Exception e) {
        errorCount++;
        System.out.println("Error scanning " + safeOrd(component) + ": " + e);
    }
}

private void removePxViews(BFolder folder) {
    Slot[] slots = folder.getSlotsArray();

    for (int i = 0; i < slots.length; i++) {
        Slot slot = slots[i];

        try {
            if (!isPxViewSlot(folder, slot))
                continue;

            pxViewCount++;

            String slotName = slot.getName();
            System.out.println((DRY_RUN ? "[DRY RUN] " : "")
                    + "Remove PxView slot: " + safeOrd(folder) + " / " + slotName);

            if (!DRY_RUN) {
                folder.remove(slotName);
                removedCount++;
            }
        } catch (Exception e) {
            errorCount++;
            System.out.println("Error removing slot " + slot.getName()
                    + " on " + safeOrd(folder) + ": " + e);
        }
    }
}

private boolean isPxViewSlot(BComponent component, Slot slot) {
    try {
        String slotName = slot.getName().toString();
        BValue value = component.get(slotName);

        if (value == null)
            return false;

        String type = value.getType().toString();
        return "baja:PxView".equals(type) || type.endsWith(":PxView");
    } catch (Exception e) {
        return false;
    }
}

private String safeOrd(BComponent component) {
    try {
        return component.getSlotPathOrd().toString();
    } catch (Exception e) {
        return component.toString();
    }
}