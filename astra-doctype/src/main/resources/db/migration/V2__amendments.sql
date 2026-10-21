-- Amendment chain for submittable DocTypes: a draft created from a cancelled document points back to it.
ALTER TABLE astra_doc ADD COLUMN amended_from text;
