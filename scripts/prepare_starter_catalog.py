"""Extract the reviewed public-domain USDA SR Legacy starter, without float rounding."""
from pathlib import Path
from decimal import Decimal
import csv, hashlib, io, json, urllib.request, uuid, zipfile

ROOT = Path(__file__).resolve().parents[1]
URL = "https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip"
SHA256 = "b80817294b8850530aaedf2e515c02593b1824f763a0ff356e5c2081643e6fd0"
NUTRIENTS = {"kcal": (1008, "KCAL"), "protein": (1003, "G"), "fat": (1004, "G"), "carbs": (1005, "G")}
CATEGORIES = {"dairy": "Молочные продукты", "eggs": "Яйца", "fruit": "Фрукты и ягоды", "vegetables": "Овощи", "legumes": "Бобовые", "grains": "Крупы и злаки", "meat-fish": "Мясо и рыба", "nuts-seeds": "Орехи и семена", "oils": "Масла", "other": "Другое"}


def prepare():
    archive = ROOT / ".local-tools/catalog-source/sr-legacy-2018-04.zip"
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        archive.write_bytes(urllib.request.urlopen(URL, timeout=120).read())
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == SHA256, "Source archive changed"
    selection = json.loads((ROOT / "scripts/starter-selection.json").read_text(encoding="utf-8"))
    assert len(selection) == len({r["fdcId"] for r in selection}) == 60
    source = zipfile.ZipFile(archive)

    def rows(name):
        key = next(k for k in source.namelist() if k.endswith("/" + name))
        return csv.DictReader(io.TextIOWrapper(source.open(key), encoding="utf-8-sig"))

    foods = {int(r["fdc_id"]): r for r in rows("food.csv")}
    legacy = {r["NDB_number"]: int(r["fdc_id"]) for r in rows("sr_legacy_food.csv")}
    definitions = {int(r["id"]): r for r in rows("nutrient.csv")}
    selected = {r["fdcId"] for r in selection}
    values = {}
    for r in rows("food_nutrient.csv"):
        fdc, nutrient = int(r["fdc_id"]), int(r["nutrient_id"])
        if fdc in selected and nutrient in {1008, 1003, 1004, 1005}:
            assert (fdc, nutrient) not in values, "Ambiguous nutrient row"
            values[fdc, nutrient] = r["amount"]
    output = []
    for item in selection:
        fdc = item["fdcId"]
        assert legacy[item["ndb"]] == fdc
        assert foods[fdc]["description"] == item["expectedEnglish"]
        assert foods[fdc]["data_type"] == "sr_legacy_food"
        nutrients = {}
        for key, (nutrient, unit) in NUTRIENTS.items():
            assert definitions[nutrient]["unit_name"].upper() == unit
            raw = values[fdc, nutrient]
            micro = Decimal(raw) * 1_000_000
            assert micro == micro.to_integral_value() and 0 <= micro <= 1_000_000_000_000
            nutrients[key] = {"nutrientId": nutrient, "unit": unit, "raw": raw, "micros": int(micro)}
        output.append({**item, "id": str(uuid.uuid3(uuid.NAMESPACE_URL, f"https://fdc.nal.usda.gov/food-details/{fdc}")), "nutrients": nutrients})
    result = {"formatVersion": 1, "source": "USDA FoodData Central / SR Legacy April 2018", "sourceUrl": URL, "sourceSha256": SHA256, "license": "CC0 1.0 / public domain", "baseGrams": 100,
              "categories": [{"code": code, "name": name, "id": str(uuid.uuid3(uuid.NAMESPACE_URL, "https://diet.local/category/" + code))} for code, name in CATEGORIES.items()], "items": output}
    destination = ROOT / "app/src/main/assets/starter_catalog.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    source.close()
    print(f"Prepared {len(output)} exact source rows; {hashlib.sha256(destination.read_bytes()).hexdigest()}")
    return result


if __name__ == "__main__":
    prepare()
