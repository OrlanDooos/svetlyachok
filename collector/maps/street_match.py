"""Одна и та же улица в OSM/Visicom и в списках облэнерго часто записана по-разному:
«Лахтинська» / «Лахтінська», «Ігоря» / «Ігора», «Воззʼєднання» / «Возєднання», «Кам’янсько-Дніпровська» / «…–…»,
«Наукового Містечка» / «Наукове містечко», «Олександра Анашкіна» / «Анашкіна».

Подбираем пару только если её подтверждают сами дома: большинство номеров улицы из карты есть в списке
облэнерго на кандидате, и кандидат — единственный такой. Ничего не угадываем: нет подтверждения — нет пары.
"""
import difflib, os, re, sys
from collections import defaultdict

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from addresses import street_keys, norm_house  # noqa: E402


def canon_house(h):
    """Номер дома как в списках облэнерго (norm_house: «34-А» = «34а»)."""
    return norm_house(h)


def loose(k):
    """Ключ без различий в написании: апостроф, двойные буквы, и/і/ї/е/є, тире, окончания порядковых («55-ї»)."""
    k = k.replace("–", "-").replace("—", "-")
    k = re.sub(r"(\d+)-?[а-яіїє]{1,2}\b", r"\1", k)
    k = k.translate(str.maketrans("иїєеыо", "іііііі"))
    k = re.sub(r"(.)\1", r"\1", k.replace("'", "").replace("-", " "))
    return k


TYPES = {"вул": "вул", "вулиця": "вул", "улица": "вул", "ул": "вул", "пров": "пров", "провулок": "пров", "переулок": "пров",
         "пер": "пров", "шосе": "ш", "ш": "ш", "просп": "пр", "проспект": "пр", "пр-т": "пр", "бул": "бул", "бульв": "бул",
         "бульвар": "бул", "пл": "пл", "площа": "пл", "площадь": "пл", "узвіз": "узв", "узв": "узв", "проїзд": "проїзд", "тупик": "туп"}


def street_type(name):
    """«вул», «пров», «ш»… или None. Улица и провулок с одним именем — разные улицы."""
    for w in re.split(r"[\s.,]+", name.lower()):
        if w in TYPES:
            return TYPES[w]
    return None


def renames(addresses, zoe_streets, aliases=None, names=None, min_share=0.6, min_houses=3):
    """addresses — адреса «улица, дом» с карты; zoe_streets — {ключ: {номера}}; names — {ключ: «вул. Назва»}.
    Возвращает {улица с карты: ключ облэнерго} для улиц, которых нет в списках под своим именем."""
    aliases = aliases or {}
    zh = {k: {canon_house(h) for h in hs} for k, hs in zoe_streets.items()}
    by_street = defaultdict(set)
    for a in addresses:
        st, _, h = a.rpartition(",")
        if st:
            by_street[st.strip()].add(canon_house(h))
    zl = defaultdict(list)
    for k in zh:
        zl[loose(k)].append(k)
    out = {}
    for st, houses in by_street.items():
        keys = [aliases.get(k, k) for k in street_keys(st)]
        if not keys or any(k in zh for k in keys) or len(houses) < min_houses:
            continue
        cands = set()
        for k in keys:
            lk = loose(k)
            cands.update(zl.get(lk, []))
            words = set(k.split())
            for zk in zh:
                zw = set(zk.split())
                # фамилия без имени («анашкіна» ⊂ «олександра анашкіна») или почти одинаковое написание
                if (zw and zw < words and max(len(w) for w in zw) >= 5) or difflib.SequenceMatcher(None, lk, loose(zk)).ratio() >= 0.85:
                    cands.add(zk)
        t = street_type(st)
        cands = {zk for zk in cands if not (t and names and street_type(names.get(zk, "")) not in (None, t))}
        ok = [zk for zk in cands if len(houses & zh[zk]) >= min_houses and len(houses & zh[zk]) / len(houses) >= min_share]
        if len(ok) == 1:
            out[st] = ok[0]
    return out
