import zipfile, re, json

jar = r'C:\Users\linch\.gradle\caches\8.14.2\transforms\094c62693fa6a7826e9f80876d769e37\transformed\icons-lucide-debug-runtime.jar'

with zipfile.ZipFile(jar) as z:
    names = z.namelist()
    # find class files in com/composables/... packages
    classes = [n for n in names if n.endswith('.class') and '/com/' in n]
    print("total class entries:", len(classes))
    # print first 30
    for c in classes[:30]:
        print(c)

    # Extract string constants of likely Lucide icon classes
    # Typically the icons module exposes an object/class containing val properties named after icons
    # Look for classes with 'Lucide' or 'Icon' in name
    icon_classes = [n for n in classes if re.search(r'(Lucide|Icon)', n)]
    print("icon-ish classes count:", len(icon_classes))
    for c in icon_classes[:50]:
        print(c)
