public void onStart() throws Exception {
    // start up code here
}

public void onExecute() throws Exception {
    // Assume you already have a BOrd input slot pointing to the Ord folder
    BOrd folderOrd = getFolderOrd(); // ordFolder is the BOrd input slot you added
    BComponent folder = null;
    try {
        folder = (BComponent) folderOrd.resolve().get(); // or use .resolve().get()
    } catch (Exception e) {
        System.err.println("Failed to resolve folder: " + e.getMessage());
    }

    if (folder == null) {
        System.err.println("Folder not found!");
        return;
    }

    double targetValue = getStartValue(); // ← Change to your desired value, e.g. 25.0
    for (BComponent child : folder.getChildren(BComponent.class)) {
        try {

            if (child instanceof BNumericWritable) {
                BNumericWritable nw = (BNumericWritable) child;
                nw.setIn10(new BStatusNumeric(targetValue));
                System.out.println("Point set successfully: " + child.getName() + " → " + targetValue);
                targetValue += 1.0; // ← Change to your desired step value, e.g. 5.0
            }

        } catch (Exception e) {
            System.err.println("Failed to set point: " + child.getName() + " → " + e.getMessage());
        }
    }
}

public void onReset() throws Exception {

    // Assume you already have a BOrd input slot pointing to the Ord folder
    BOrd folderOrd = getFolderOrd(); // ordFolder is the BOrd input slot you added
    BComponent folder = null;
    try {
        folder = (BComponent) folderOrd.resolve().get(); // or use .resolve().get()
    } catch (Exception e) {
        System.err.println("Failed to resolve folder: " + e.getMessage());
    }

    if (folder == null) {
        System.err.println("Folder not found!");
        return;
    }

    // double targetValue = getStartValue(); // ← Change to your desired value, e.g.
    // 25.0
    for (BComponent child : folder.getChildren(BComponent.class)) {
        try {

            if (child instanceof BNumericWritable) {
                BNumericWritable nw = (BNumericWritable) child;
                nw.setIn10(new BStatusNumeric(0, BStatus.nullStatus));
                nw.setFallback(new BStatusNumeric(0));
                System.out.println("Point value reset");

            }

        } catch (Exception e) {
            System.err.println("Failed to set point: " + child.getName() + " → " + e.getMessage());
        }
    }
}

public void onStop() throws Exception {
    // shutdown code here
}
