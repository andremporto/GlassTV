import os
from PIL import Image

src_dir = 'scratch/wallpapers_original'
dst_dir = 'app/assets/wallpapers'
thumb_dir = os.path.join(dst_dir, 'thumbs')

os.makedirs(dst_dir, exist_ok=True)
os.makedirs(thumb_dir, exist_ok=True)

# Mapping dictionary: source filename -> semantic kebab-case name
MAPPING = {
    "2560x1440.jpg": "monochrome-silver.webp",
    "5ab0b3110486337.5feef52c7a3e3.jpg": "pastel-mountain-sky.webp",
    "Ventura dark.jpg": "macos-ventura-dark.webp",
    "andreas-gucklhorn-mawU2PoJWfU-unsplash.jpg": "pine-forest-lake.webp",
    "andrew-neel-jtsW--Z6bFw-unsplash.jpg": "dark-minimal-desk.webp",
    "ashim-d-silva-WeYamle9fDM-unsplash.jpg": "alpine-sunrise-glow.webp",
    "avatar-aang-the-3840x2160-27060.jpg": "avatar-aang.webp",
    "beautiful-beach-scenery-nature-32-4K.jpg": "tropical-paradise-beach.webp",
    "chibi-luffy-one-3840x2160-13063.png": "chibi-luffy.webp",
    "gear-5-monkey-d-3840x2160-17522.jpg": "gear-5-luffy.webp",
    "golden-leaves-4kjpg.jpg": "autumn-leaves.webp",
    "ichigo-kurosaki-3840x2160-27177.jpg": "ichigo-bleach.webp",
    "infinity-2518798.jpg": "infinite-bridge-fog.webp",
    "iswanto-arif-OJ74pFtrYi0-unsplash.jpg": "night-sky-stars.webp",
    "jennifer-latuperisa-andresen-KuODZUlkhM4-unsplash.jpg": "dolomites-foggy-road.webp",
    "jesse-collins-znekTMMoBrM-unsplash.jpg": "coastal-ocean-cliffs.webp",
    "joshua-bartell-6vvIBTvL90A-unsplash.jpg": "autumn-forest-trail.webp",
    "kakashi-hatake-in-naruto-xs-3840x2160.jpg": "kakashi-naruto.webp",
    "keith-misner-h0Vxgz5tyXA-unsplash.jpg": "lightning-night-storm.webp",
    "mark-eder-R9OS29xJb-8-unsplash.jpg": "minimal-dune.webp",
    "markus-spiske-3tbtq3oCQ1s-unsplash.jpg": "fern-foliage-nature.webp",
    "monkey-d-luffy-3840x2160-26035.jpg": "luffy-sunset-sea.webp",
    "nathan-dumlao-J2gEgTPM_OA-unsplash.jpg": "roasted-coffee-beans.webp",
    "nattu-adnan-Ai2TRdvI6gM-unsplash.jpg": "maldives-ocean-pier.webp",
    "nezuko-kamado-3840x2160-14025.png": "nezuko-kimetsu.webp",
    "nezuko-kamado-demon-3840x2160-9331.jpg": "nezuko-demon-mode.webp",
    "nezuko-kamado-pink-3840x2160-16946.jpg": "nezuko-cherry-blossom.webp",
    "pain-nagato-black-3840x2160-26691.jpg": "pain-nagato.webp",
    "pexels-adrien-olichon-2387793.jpg": "dark-geometric-texture.webp",
    "qingbao-meng-01_igFr7hd4-unsplash.jpg": "zen-bamboo-forest.webp",
    "rice-terraces-nature-scenery-4k-wallpaper-1104a.jpg": "rice-terraces-valley.webp",
    "satoru-gojo-fallen-hero-gf-3840x2160.jpg": "satoru-gojo-void.webp",
    "scenery-nature-lake-dock-uhdpaper.com-4K-6.2654.jpg": "mountain-lake-dock.webp",
    "simon-2JONUbTfN38-unsplash.jpg": "swiss-alps-snow.webp",
    "solo-leveling-3840x2160-26864.jpg": "solo-leveling-shadow.webp",
    "straw-hat-luffy-one-3840x2160-22500.jpg": "straw-hat-luffy.webp",
    "sung-jinwoo-high-3840x2160-27166.jpg": "sung-jinwoo-monarch.webp",
    "three-sword-style-3840x2160-26027.jpg": "roronoa-zoro-santoryu.webp",
    "vecteezy_black-stone-texture-background_22504984_241.jpeg": "black-slate-texture-1.webp",
    "vecteezy_black-stone-texture-background_22504985_623.jpeg": "black-slate-texture-2.webp",
    "wade-meng-LgCj9qcrfhI-unsplash.jpg": "city-sunset-skyline.webp",
    "wall1.jpg": "gray-mist-valley.webp",
    "wall10.png": "warm-dune-waves.webp",
    "wall12.png": "minimal-dark-black.webp",
    "wall13.png": "minimal-light-white.webp",
    "wall23.png": "abstract-sunset-glow.webp",
    "wall24.png": "abstract-fluid-flow.webp",
    "wall25.png": "abstract-blue-wave.webp",
    "wall4.jpg": "golden-canyon-dunes.webp",
    "wall8.png": "crimson-fluid-flow.webp",
    "widescreen 16.10 - 2560x1600.jpg": "foggy-pier.webp",
    "wp9081772-nature-4k-green-wallpapers.jpg": "emerald-nature-leaves.webp",
    "zdenek-machacek-boCk0rIiOSw-unsplash.jpg": "winter-snow-forest.webp"
}

def center_crop_16_9(img):
    w, h = img.size
    target_ratio = 16.0 / 9.0
    current_ratio = w / h
    
    if current_ratio > target_ratio:
        # Wider than 16:9, crop sides
        new_w = int(round(h * target_ratio))
        left = (w - new_w) // 2
        return img.crop((left, 0, left + new_w, h))
    elif current_ratio < target_ratio:
        # Taller than 16:9, crop top & bottom
        new_h = int(round(w / target_ratio))
        top = (h - new_h) // 2
        return img.crop((0, top, w, top + new_h))
    return img

print("Processing wallpapers...")
results = []
total_orig_size = 0
total_1080_size = 0
total_thumb_size = 0

for orig_name, new_name in sorted(MAPPING.items()):
    src_path = os.path.join(src_dir, orig_name)
    if not os.path.exists(src_path):
        print(f"Warning: {src_path} not found!")
        continue
    
    orig_size = os.path.getsize(src_path)
    total_orig_size += orig_size
    
    with Image.open(src_path) as img:
        # Convert RGBA/P to RGB for clean wallpapers without alpha
        rgb_img = img.convert('RGB')
        
        # Center-crop to 16:9
        cropped = center_crop_16_9(rgb_img)
        
        # Resize to 1920x1080
        full_1080 = cropped.resize((1920, 1080), Image.Resampling.LANCZOS)
        dst_1080_path = os.path.join(dst_dir, new_name)
        full_1080.save(dst_1080_path, format='WEBP', quality=82, method=6)
        size_1080 = os.path.getsize(dst_1080_path)
        total_1080_size += size_1080
        
        # Generate thumbnail 320x180
        thumb_180 = full_1080.resize((320, 180), Image.Resampling.LANCZOS)
        dst_thumb_path = os.path.join(thumb_dir, new_name)
        thumb_180.save(dst_thumb_path, format='WEBP', quality=80, method=6)
        size_thumb = os.path.getsize(dst_thumb_path)
        total_thumb_size += size_thumb
        
        results.append({
            "orig_name": orig_name,
            "new_name": new_name,
            "orig_size_kb": orig_size // 1024,
            "size_1080_kb": size_1080 // 1024,
            "size_thumb_kb": size_thumb // 1024
        })
        print(f"Done {new_name}: {orig_size//1024}KB -> 1080p: {size_1080//1024}KB, thumb: {size_thumb//1024}KB")

print(f"\nTOTALS:")
print(f"Originals: {total_orig_size / (1024*1024):.2f} MB")
print(f"1080p WebP: {total_1080_size / (1024*1024):.2f} MB")
print(f"Thumbs WebP: {total_thumb_size / (1024*1024):.2f} MB")
print(f"Total New Assets: {(total_1080_size + total_thumb_size) / (1024*1024):.2f} MB")
print(f"Reduction: {100 - (total_1080_size + total_thumb_size)*100/total_orig_size:.1f}%")
